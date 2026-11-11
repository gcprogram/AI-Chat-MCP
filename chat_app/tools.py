"""Werkzeuge per Textmarker im Chat.

Kleine Modelle können kein zuverlässiges Function-Calling. Stattdessen steht im System-Prompt, dass das Modell
für einen Werkzeugaufruf eine Zeile in diesem Format schreiben soll:

    [[mcp:NAME|ARGUMENT]]            Beispiel: [[mcp:web-search|KI Nachrichten heute]]
    [[mcp:NAME|ARG1|ARG2]]           mehrere Argumente: mit | getrennt (Reihenfolge wie in der Werkzeugliste)
    [[mcp:NAME]]                     ohne Argumente

Das Programm beobachtet die Ausgabe, bricht die Antwort beim ersten vollständigen Marker ab (alles danach wäre
vom Modell erfundenes Ergebnis), führt das MCP-Werkzeug aus und gibt das Ergebnis als nächste Nachricht zurück.
Der Parser ist absichtlich tolerant (Groß-/Kleinschreibung, '_' = '-', Anführungszeichen, 'key=value',
JSON-Objekt, einfache Klammern, ':' statt '|')."""
from __future__ import annotations

import json
import logging
import re
import time
from dataclasses import dataclass, field, replace
from pathlib import Path
from typing import Any

from .config import APP_DIR, MCP_CONFIG_PATH
from .mcp_client import McpClient, McpError, McpServer
from .mcp_http import HttpMcpServer

log = logging.getLogger(__name__)

CALL_RE = re.compile(
    r"\[{1,2}[ \t]*mcp[ \t]*[:/][ \t]*(?P<name>[\w.\-]+)[ \t]*(?:[|:][ \t]*(?P<args>[^\]\n]*?))?[ \t]*\]{1,2}",
    re.IGNORECASE)
_QUOTES = {'"': '"', "'": "'", "„": "“", "“": "”", "«": "»", "»": "«"}


@dataclass(frozen=True)
class ToolCall:
    name: str
    args: str
    raw: str
    start: int
    end: int
    arguments: dict | None = None  # nativer Aufruf: die echten (JSON-)Argumente statt des Marker-Textes


def find_call(text: str) -> ToolCall | None:
    match = CALL_RE.search(text)
    if not match:
        return None
    return ToolCall(match["name"], (match["args"] or "").strip(), match.group(0), match.start(), match.end())


def split_segments(text: str) -> list[tuple[str, str]]:
    """Zerlegt Text in ('text', ...) und ('call', 'name: argumente') - für die Anzeige im Chat."""
    segments, pos = [], 0
    for match in CALL_RE.finditer(text):
        if match.start() > pos:
            segments.append(("text", text[pos:match.start()]))
        args = (match["args"] or "").strip()
        segments.append(("call", f"{match['name']}: {args}" if args else match["name"]))
        pos = match.end()
    if pos < len(text):
        segments.append(("text", text[pos:]))
    return segments


def neutralize(text: str) -> str:
    """Marker in Werkzeug-Ergebnissen (z. B. aus Webseiten) entschärfen: Ergebnisse dürfen keine Aufrufe auslösen."""
    return CALL_RE.sub(lambda m: m.group(0).replace("[", "(").replace("]", ")"), text)


def normalize_name(name: str) -> str:
    return name.strip().lower().replace("_", "-")


# --- Werkzeugbeschreibung und Argumente -----------------------------------------------------------

class ToolArgError(Exception):
    pass


@dataclass
class Param:
    name: str
    type: str = "string"
    required: bool = False
    description: str = ""


def _unquote(value: Any) -> Any:
    if isinstance(value, str):
        value = value.strip()
        if len(value) >= 2 and _QUOTES.get(value[0]) == value[-1]:
            return value[1:-1].strip()
    return value


def _split_args(text: str) -> list[str] | dict[str, Any]:
    text = text.strip()
    if text.startswith("{"):
        try:
            data = json.loads(text)
            if isinstance(data, dict):
                return data
        except ValueError:
            pass
        quoted = [a or b for a, b in re.findall(r'"([^"]*)"|\'([^\']*)\'', text)]
        if len(quoted) >= 2 and len(quoted) % 2 == 0:  # {"suchbegriff", "AI News"} -> Schlüssel/Wert-Paare
            return dict(zip(quoted[::2], quoted[1::2]))
        if quoted:
            return quoted
        text = text.strip("{} \t")
    parts = [p.strip() for p in text.split("|")] if text else []
    while parts and not parts[-1]:
        parts.pop()
    return parts


def _coerce(param: Param, value: Any) -> Any:
    if not isinstance(value, str):
        return value
    try:
        if param.type == "integer":
            return int(float(value.replace(",", ".")))
        if param.type == "number":
            return float(value.replace(",", "."))
        if param.type == "boolean":
            return value.strip().lower() in ("true", "ja", "yes", "1", "wahr")
        if param.type in ("array", "object"):
            try:
                return json.loads(value)
            except ValueError:
                return [value] if param.type == "array" else {"value": value}
    except ValueError:
        pass
    return value


RESULT_MODEL, RESULT_SHOW, RESULT_DISCARD, RESULT_BOTH = "model", "show", "discard", "both"
RESULT_MODES = (RESULT_MODEL, RESULT_SHOW, RESULT_DISCARD, RESULT_BOTH)


@dataclass
class ToolSpec:
    alias: str
    server: str
    remote_name: str
    description: str = ""
    params: list[Param] = field(default_factory=list)
    example: str = ""
    read_only: bool = False
    # Nur bei Aliasen aus dem Block "aliases":
    fixed: dict[str, Any] = field(default_factory=dict)  # feste Argumente, die das Modell nicht sieht
    defaults: dict[str, Any] = field(default_factory=dict)  # Ersatz für fehlende Argumente
    result: str = RESULT_MODEL  # model: Modell antwortet | show: Klartext im Chat | discard: nur Statuszeile | both: Klartext UND Modell antwortet
    status: str = ""  # Statuszeile bei discard
    confirm: bool | None = None  # None = globale Einstellung "Vor jedem Werkzeugaufruf nachfragen"
    remember: bool = False  # Ergebnis bei show/discard dem Modell im Verlauf zeigen
    # Makro: Schritte ("call", Alias, Argumente) oder ("wait", Sekunden); dann gibt es keinen eigenen Server-Aufruf
    macro: list[tuple] = field(default_factory=list)
    # Tastensatz einer Fernbedienung ("buttons": true): erlaubte Beschriftungen (Argument "button"), zuletzt "off"
    buttons: list[str] = field(default_factory=list)

    @property
    def usage(self) -> str:
        return "[[mcp:" + "|".join([self.alias] + [f"<{p.name}>" for p in self.params]) + "]]"

    def bind(self, args: str) -> dict[str, Any]:
        """Marker-Text -> benannte Argumente der sichtbaren Parameter (noch ohne Standardwerte und Typumwandlung)."""
        names = {p.name for p in self.params}
        given: dict[str, Any] = {}
        positional: list[Any] = []
        parsed = _split_args(args)
        if isinstance(parsed, dict):
            for key, value in parsed.items():
                if key in names and key not in given:
                    given[key] = _unquote(value)
                else:  # unbekannter Schlüssel ("searchstring" statt "query"): Wert zählt positionell
                    positional.append(_unquote(value))
        else:
            for part in parsed:
                match = re.match(r"^([A-Za-z_]\w*)\s*=\s*(.*)$", part, re.S)
                if match and match[1] in names and match[1] not in given:
                    given[match[1]] = _unquote(match[2])
                else:
                    positional.append(_unquote(part))
        free = [p for p in self.params if p.name not in given]
        for param, value in zip(free, positional):
            given[param.name] = value
        extras = positional[len(free):]
        if extras and len(self.params) == 1 and isinstance(given.get(self.params[0].name), str):
            given[self.params[0].name] = " ".join([given[self.params[0].name], *map(str, extras)])
        return given

    def resolve(self, args: str, named: dict[str, Any] | None = None) -> dict[str, Any]:
        """Marker-Argumente (oder bei nativen Aufrufen schon benannte) -> Argumente des MCP-Werkzeugs
        (Standardwerte, Typumwandlung, feste Argumente)."""
        names = {p.name for p in self.params}
        if named is None:
            given = self.bind(args)
        else:
            given = {k: _unquote(v) for k, v in named.items() if k in names}
        for name, value in self.defaults.items():
            if given.get(name) in (None, ""):
                given[name] = value
        missing = [p.name for p in self.params if p.required and given.get(p.name) in (None, "")]
        if missing:
            raise ToolArgError(f"Es fehlt: {', '.join(missing)}. Richtiges Format: {self.usage}")
        by_name = {p.name: p for p in self.params}
        arguments = {k: _coerce(by_name[k], v) for k, v in given.items() if k in by_name}
        arguments.update(self.fixed)  # feste Argumente gewinnen immer
        return arguments

    def prompt_line(self, only: list[str] | None = None) -> str:
        """Zeile im Prompt. only: bei einem Tastensatz die für diesen Companion erlaubten Tasten."""
        description = self.description.rstrip(".") + "." if self.description else ""
        optional = [p.name for p in self.params if not p.required]
        note = f" Optional: {', '.join(optional)}." if optional else ""
        example = self.example or self.usage
        if self.buttons:
            labels = self.allowed_buttons(only)
            note = f" Buttons: {', '.join(labels)}."
            if not self.example or self.example_label() not in labels:
                example = f"[[mcp:{self.alias}|{labels[0] if labels else 'P1'}]]"
        return f"- {self.alias}: {description}{note} Example: {example}".replace("  ", " ")

    def example_label(self) -> str:
        match = re.match(r"^\[\[mcp:[^|\]]+\|([^|\]]+)", self.example)
        return match[1].strip() if match else ""

    def allowed_buttons(self, only: list[str] | None = None) -> list[str]:
        """Die Tasten, die der Companion benutzen darf (leere/fehlende Auswahl = alle)."""
        if not only:
            return list(self.buttons)
        wanted = {o.strip().lower() for o in only}
        return [b for b in self.buttons if b.lower() in wanted]


def _first_sentence(text: str, limit: int = 140) -> str:
    text = " ".join(str(text or "").split())
    match = re.search(r"[.!?](?:\s|$)", text)
    if match:
        text = text[:match.end()].strip()
    return text if len(text) <= limit else text[:limit - 1].rstrip() + "…"


def _params_from_schema(schema: Any, only: list[str] | None) -> list[Param]:
    schema = schema if isinstance(schema, dict) else {}
    props = schema.get("properties") if isinstance(schema.get("properties"), dict) else {}
    required = [r for r in schema.get("required", []) if isinstance(r, str)]
    order = [n for n in required if n in props] + [n for n in props if n not in required]
    if only is not None:
        order = [n for n in only if n in props]
    return [Param(n, str((props[n] or {}).get("type") or "string") if isinstance(props[n], dict) else "string",
                  n in required, str((props[n] or {}).get("description", "")) if isinstance(props[n], dict) else "")
            for n in order]


@dataclass(frozen=True)
class ToolOutcome:
    tool: str
    ok: bool
    text: str
    mode: str = RESULT_MODEL  # Fehler gehen immer ans Modell, damit es dem Nutzer Bescheid sagen kann
    status: str = ""
    remember: bool = False


RETRYABLE_PREFIXES = ("Unbekanntes Werkzeug", "Es fehlt:", "Unbekannte Taste")


def is_retryable_error(text: str) -> bool:
    """Fehler, bei denen das Modell den Aufruf sofort korrigieren soll (falscher Name, fehlendes Argument), statt sich zu entschuldigen."""
    return text.startswith(RETRYABLE_PREFIXES)


def result_message(tool: str, text: str, ok: bool, user_name: str, tool_lines: list[str] | None = None) -> str:
    """Text der Nachricht, mit der das Werkzeugergebnis an das Modell zurückgegeben wird. tool_lines: die Zeilen der freigeschalteten
    Werkzeuge (wie im Prompt): Bei einem falschen Namen steht die Liste noch einmal ganz unten, wo das kleine Modell sie sieht."""
    if not ok and tool_lines and is_retryable_error(text):
        return (f"[Error from tool call {tool}] {text}\n"
                "Tools you can call (use exactly one of these names):\n" + "\n".join(tool_lines) + "\n"
                "Write the corrected call now as one line [[mcp:NAME|ARGUMENT]]. Do not apologize and do not explain.")
    if ok:
        return (f"[Ergebnis von {tool}]\n{text}\n[Ende des Ergebnisses]\n"
                f"Antworte {user_name} jetzt kurz und in deinen eigenen Worten anhand dieses Ergebnisses. "
                "Schreibe keinen neuen Werkzeugaufruf, außer dir fehlt noch eine Information.")
    return (f"[Fehler bei {tool}] {text}\n"
            f"Sage {user_name} kurz, dass es nicht geklappt hat, oder versuche den Aufruf im richtigen Format erneut.")


class ToolRegistry:
    """Alle Werkzeuge der konfigurierten MCP-Server. Pro Companion wird eine Auswahl freigeschaltet."""

    def __init__(self) -> None:
        self.servers: dict[str, McpClient] = {}
        self.tools: dict[str, ToolSpec] = {}  # was das Modell benutzen darf (Aliase)
        self.remote: dict[tuple[str, str], ToolSpec] = {}  # alle Werkzeuge der Server, auch unsichtbare
        self.errors: list[str] = []
        self._buttons: dict[str, dict[str, str]] = {}  # Server -> Schlüssel (Label, Hex, Dezimal; klein) -> Code "0x11"
        self._button_labels: dict[str, list[str]] = {}  # Server -> Beschriftungen in der Reihenfolge der Fernbedienung
        self.declared: list[str] = []  # alle Aliase der Konfiguration, auch die gerade nicht verfügbaren (Server aus/nicht erreichbar)

    # --- Aufbau --------------------------------------------------------------------------------

    def add_server(self, name: str, server: McpClient, overrides: dict | None = None,
                   *, expose_raw: bool = True) -> list[str]:
        """Startet den Server (blockierend) und registriert seine Werkzeuge. Liefert die neuen Aliase.
        Mit expose_raw=False bleiben die Werkzeuge unsichtbar und sind nur über "aliases" erreichbar."""
        server.start()
        try:
            remote_tools = server.list_tools()
        except Exception:
            server.close()
            raise
        self.servers[name] = server
        added = []
        for tool in remote_tools:
            override = (overrides or {}).get(tool["name"])
            override = override if isinstance(override, dict) else {}
            alias = normalize_name(str(override.get("alias") or tool["name"]))
            if alias in self.tools:
                alias = f"{normalize_name(name)}-{alias}"
            only = override.get("args") if isinstance(override.get("args"), list) else None
            annotations = tool.get("annotations") if isinstance(tool.get("annotations"), dict) else {}
            spec = ToolSpec(
                alias=alias, server=name, remote_name=tool["name"],
                description=str(override.get("hint") or _first_sentence(tool.get("description", ""))),
                params=_params_from_schema(tool.get("inputSchema"), only),
                example=str(override.get("example") or ""), read_only=bool(annotations.get("readOnlyHint")))
            self.remote[(name, tool["name"])] = spec
            if expose_raw:
                self.tools[alias] = spec
                added.append(alias)
        return added

    def add_aliases(self, aliases: Any, skip_servers: frozenset[str] = frozenset()) -> list[str]:
        """Registriert Kurznamen aus dem Block "aliases" (Fehler landen in self.errors). Liefert die neuen Aliase.
        Aliase auf bewusst deaktivierte Server (skip_servers) werden still übergangen."""
        if not isinstance(aliases, dict):
            if aliases is not None:
                self.errors.append("'aliases' ist kein Objekt")
            return []
        added = []
        skipped: set[str] = set()  # Aliase auf deaktivierte/ausgefallene Server: Makros, die sie aufrufen, entfallen still
        for raw_name, entry in aliases.items():
            if normalize_name(str(raw_name)) not in self.declared:
                self.declared.append(normalize_name(str(raw_name)))
            if isinstance(entry, dict) and entry.get("server") in skip_servers:
                skipped.add(normalize_name(str(raw_name)))
                continue
            alias = normalize_name(str(raw_name))
            try:
                self.tools[alias] = self._build_alias(alias, entry)
            except ValueError as exc:
                self.errors.append(f"Alias '{alias}': {exc}")
            else:
                added.append(alias)
        return self._check_macros(added, skipped)

    def button_codes(self, server: str) -> dict[str, str]:
        """Die Tasten eines Servers mit Werkzeug "buttons" (Fernbedienung): Schlüssel (Label, Hex, Dezimalzahl; klein) -> Code "0x11".
        Die Beschriftungen stehen nur dort (im Profil der Fernbedienung), nicht noch einmal in mcp_servers.json."""
        if server not in self._buttons:
            if (server, "buttons") not in self.remote:
                raise ValueError(f"Server '{server}' hat kein Werkzeug 'buttons'")
            text, is_error = self.servers[server].call_tool("buttons", {}, 15.0)
            try:
                items = json.loads(text)
                if is_error or not isinstance(items, list):
                    raise ValueError
            except ValueError:
                raise ValueError(f"Tasten von '{server}' nicht lesbar: {text[:80]}") from None
            codes: dict[str, str] = {}
            labels: list[str] = []
            for item in items:
                if not isinstance(item, dict) or not isinstance(item.get("code"), int):
                    continue
                code = f"0x{item['code']:02x}"
                labels.append(str(item.get("label") or code))
                for key in (item.get("label"), item.get("hex"), code, str(item["code"])):
                    if key not in (None, ""):
                        codes.setdefault(str(key).strip().lower(), code)
            self._buttons[server] = codes
            self._button_labels[server] = labels
        return self._buttons[server]

    def _build_button_set(self, alias: str, entry: dict) -> ToolSpec:
        """Alias "buttons": true: EIN Werkzeug für alle Tasten der Fernbedienung, die Taste ist das Argument (wie der Ort beim Wetter).
        Die Beschriftungen kommen vom Server; die Taste "off" ruft dessen Werkzeug "off" auf."""
        server = str(entry.get("server") or "")
        if (server, "send") not in self.remote:
            raise ValueError(f"Werkzeug '{server}/send' nicht gefunden (Server nicht gestartet?)")
        self.button_codes(server)
        labels = list(self._button_labels[server])
        if (server, "off") in self.remote:
            labels.append("off")
        mode = str(entry.get("result") or RESULT_DISCARD)
        if mode not in RESULT_MODES:
            raise ValueError(f"'result' muss {', '.join(RESULT_MODES)} sein")
        confirm = entry.get("confirm")
        if confirm is not None and not isinstance(confirm, bool):
            raise ValueError("'confirm' muss true oder false sein")
        return ToolSpec(
            alias=alias, server=server, remote_name="send", description=str(entry.get("hint") or "Presses a button on the remote control"),
            params=[Param("button", "string", True, "Label of the button")], example=str(entry.get("example") or ""),
            result=mode, status=str(entry.get("status") or ""), confirm=confirm, remember=bool(entry.get("remember", False)),
            buttons=labels)

    def _build_macro(self, alias: str, entry: dict) -> ToolSpec:
        raw = entry.get("macro")
        if not isinstance(raw, list) or not raw:
            raise ValueError("'macro' muss eine nicht leere Liste sein")
        if len(raw) > 20:
            raise ValueError("'macro' hat zu viele Schritte (höchstens 20)")
        steps: list[tuple] = []
        for step in raw:
            wait = step.get("wait") if isinstance(step, dict) else step
            if isinstance(wait, (int, float)) and not isinstance(wait, bool):
                if not 0 < wait <= 60:
                    raise ValueError("eine Wartezeit muss zwischen 0 und 60 Sekunden liegen")
                steps.append(("wait", float(wait)))
            elif isinstance(step, str) and step.strip():
                name, _, args = step.partition("|")
                steps.append(("call", normalize_name(name), args.strip()))
            else:
                raise ValueError(f"Schritt nicht verständlich: {step!r} (erlaubt: \"alias\", \"alias|Argument\", 3, {{\"wait\": 3}})")
        if not any(s[0] == "call" for s in steps):
            raise ValueError("'macro' braucht mindestens einen Aufruf")
        mode = str(entry.get("result") or RESULT_DISCARD)
        if mode not in RESULT_MODES:
            raise ValueError(f"'result' muss {', '.join(RESULT_MODES)} sein")
        confirm = entry.get("confirm")
        if confirm is not None and not isinstance(confirm, bool):
            raise ValueError("'confirm' muss true oder false sein")
        return ToolSpec(
            alias=alias, server="", remote_name="", description=str(entry.get("hint") or "Runs several tools one after another"),
            example=str(entry.get("example") or ""), result=mode, status=str(entry.get("status") or ""), confirm=confirm,
            remember=bool(entry.get("remember", False)), macro=steps)

    def _check_macros(self, names: list[str], skipped: set[str] = frozenset()) -> list[str]:
        """Prüft nach dem Aufbau aller Aliase, ob jedes Makro nur vorhandene Werkzeuge aufruft (keine Makros in Makros)."""
        ok = []
        for name in names:
            spec = self.tools.get(name)
            if spec is None or not spec.macro:
                ok.append(name)
                continue
            if any(step[0] == "call" and step[1] in skipped for step in spec.macro):
                del self.tools[name]  # ruft ein Werkzeug eines abgeschalteten Servers auf: kein Fehler, das Makro gibt es nur dann nicht
                continue
            problem = next((f"'{step[1]}' gibt es nicht" if step[1] not in self.tools else f"'{step[1]}' ist selbst ein Makro"
                            for step in spec.macro if step[0] == "call"
                            and (step[1] not in self.tools or self.tools[step[1]].macro)), "")
            if problem:
                del self.tools[name]
                self.errors.append(f"Alias '{name}': {problem}")
            else:
                ok.append(name)
        return ok

    def _build_alias(self, alias: str, entry: Any) -> ToolSpec:
        if not isinstance(entry, dict):
            raise ValueError("Eintrag ist kein Objekt")
        if "macro" in entry:
            return self._build_macro(alias, entry)
        if entry.get("buttons") is True:
            return self._build_button_set(alias, entry)
        server, tool = str(entry.get("server") or ""), str(entry.get("tool") or "")
        button = str(entry["button"]).strip() if entry.get("button") not in (None, "") else ""
        if button:  # Taste der Fernbedienung per Beschriftung: send mit dem Code, den "buttons" dazu meldet
            tool = tool or "send"
        base = self.remote.get((server, tool))
        if base is None:
            raise ValueError(f"Werkzeug '{server}/{tool}' nicht gefunden (Server nicht gestartet?)")
        entry = dict(entry)
        if button:
            codes = self.button_codes(server)
            code = codes.get(button.lower())
            if code is None:
                known = sorted({k for k in codes if not re.fullmatch(r"(0x)?[0-9a-f]{1,3}", k)}) or sorted(codes)
                raise ValueError(f"Taste '{button}' gibt es auf '{server}' nicht (bekannt: {', '.join(known[:30])})")
            entry["args"] = {**(entry.get("args") or {}), "code": code}
            entry.setdefault("result", RESULT_DISCARD)
            entry.setdefault("status", button)
        fixed, defaults = entry.get("args") or {}, entry.get("defaults") or {}
        if not isinstance(fixed, dict) or not isinstance(defaults, dict):
            raise ValueError("'args' und 'defaults' müssen Objekte sein")
        by_name = {p.name: p for p in base.params}
        names = entry.get("params")
        if names is None:  # ohne Angabe: alle Parameter, die nicht fest vorgegeben sind
            names = [p.name for p in base.params if p.name not in fixed]
        if not isinstance(names, list):
            raise ValueError("'params' muss eine Liste sein")
        unknown = [n for n in [*names, *defaults] if n not in by_name]
        if unknown:
            raise ValueError(f"unbekannte Parameter {', '.join(map(str, unknown))} "
                             f"(das Werkzeug kennt: {', '.join(by_name) or 'keine'})")
        mode = str(entry.get("result") or RESULT_MODEL)
        if mode not in RESULT_MODES:
            raise ValueError(f"'result' muss {', '.join(RESULT_MODES)} sein")
        confirm = entry.get("confirm")
        if confirm is not None and not isinstance(confirm, bool):
            raise ValueError("'confirm' muss true oder false sein")
        remember = entry.get("remember")
        if remember is None:
            remember = mode == RESULT_SHOW  # angezeigte Wetterdaten braucht das Modell für Folgefragen
        params = [replace(by_name[n], required=by_name[n].required and n not in defaults) for n in names]
        return ToolSpec(
            alias=alias, server=server, remote_name=tool, params=params, read_only=base.read_only,
            description=str(entry.get("hint") or base.description), example=str(entry.get("example") or ""),
            fixed=dict(fixed), defaults=dict(defaults), result=mode, status=str(entry.get("status") or ""),
            confirm=confirm, remember=bool(remember))

    @staticmethod
    def _server_from_entry(name: str, entry: dict, base_dir: Path) -> McpClient:
        """"command" = stdio-Unterprozess, "url" = HTTP-Server (headers mit ${UMGEBUNGSVARIABLE}, cert = Vertrauensanker)."""
        if entry.get("url"):
            headers = entry.get("headers") or {}
            if not isinstance(headers, dict):
                raise ValueError("'headers' muss ein Objekt sein")
            return HttpMcpServer(str(entry["url"]), {str(k): str(v) for k, v in headers.items()},
                                 str(entry["cert"]) if entry.get("cert") else None, name)
        return McpServer(str(entry["command"]), [str(a) for a in entry.get("args", [])],
                         {str(k): str(v) for k, v in (entry.get("env") or {}).items()},
                         str(entry.get("cwd") or base_dir), name)

    def start_from_config(self, path: Path | str = MCP_CONFIG_PATH, base_dir: Path = APP_DIR) -> None:
        """Liest mcp_servers.json (Format wie bei Claude Desktop: "mcpServers"; zusätzlich "tools"-Hinweise)."""
        path = Path(path)
        if not path.exists():
            return
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
            servers = data.get("servers") or data.get("mcpServers") or {}
            if not isinstance(servers, dict):
                raise ValueError("'servers' ist kein Objekt")
        except (OSError, ValueError, AttributeError) as exc:
            self.errors.append(f"{path.name} nicht lesbar: {exc}")
            return
        failed: set[str] = set()
        for name, entry in servers.items():
            if not isinstance(entry, dict) or entry.get("disabled") or not (entry.get("command") or entry.get("url")):
                continue
            try:
                server = self._server_from_entry(name, entry, base_dir)
                self.add_server(name, server, entry.get("tools"), expose_raw=entry.get("raw", True) is not False)
            except (McpError, OSError, ValueError, KeyError) as exc:
                log.warning("MCP-Server '%s' nicht verfügbar: %s", name, exc)
                self.errors.append(f"{name}: {exc}")
                failed.add(name)
        disabled = frozenset(n for n, e in servers.items() if isinstance(e, dict) and e.get("disabled"))
        self.add_aliases(data.get("aliases"), disabled | failed)  # ein Fehler pro Server, nicht einer pro Alias

    def shutdown(self) -> None:
        for server in self.servers.values():
            server.close()

    # --- Nutzung -------------------------------------------------------------------------------

    def available(self) -> list[str]:
        return sorted(self.tools)

    def selectable(self) -> list[str]:
        """Alles, was ein Companion freischalten kann: die verfügbaren Werkzeuge und die in der Konfiguration stehenden, deren Server
        gerade nicht läuft (sie gelten, sobald der Server wieder da ist)."""
        return sorted(set(self.tools) | set(self.declared))

    def prompt_lines(self, aliases: list[str], choices: dict[str, list[str]] | None = None) -> list[str]:
        """Die Zeilen der Werkzeuge wie im Prompt (Name, Beschreibung, Beispiel)."""
        choices = choices or {}
        return [self.tools[a].prompt_line(choices.get(a)) for a in dict.fromkeys(normalize_name(x) for x in aliases) if a in self.tools]

    def prompt_section(self, aliases: list[str], choices: dict[str, list[str]] | None = None) -> str:
        choices = choices or {}
        specs = [self.tools[a] for a in dict.fromkeys(normalize_name(x) for x in aliases) if a in self.tools]
        if not specs:
            return ""
        return "\n".join([  # englisch: kleine Modelle folgen englischen Anweisungen zuverlässiger
            "Tools:",
            "You can use tools. To call one, write exactly one line in the format [[mcp:NAME|ARGUMENT]] at the very "
            "start of your reply and nothing after it. The program runs the tool; you do not say it is done yourself.",
            "- Never invent a result and never guess what a tool returns.",
            "- Use a tool only when you really need it; otherwise just answer normally.",
            "- Separate several arguments with |. No quotation marks around arguments.",
            "Available tools:",
            *(spec.prompt_line(choices.get(spec.alias)) for spec in specs),
        ])

    # --- Natives Tool-Calling (Parameter "tools" der Chat-API) ------------------------------------

    def native_tools(self, aliases: list[str], choices: dict[str, list[str]] | None = None) -> list[dict]:
        """Funktionsbeschreibungen im OpenAI-Format. Die Aliase machen sie klein: feste Argumente fehlen,
        meist bleibt höchstens ein Parameter."""
        tools = []
        for alias in dict.fromkeys(normalize_name(x) for x in aliases):
            spec = self.tools.get(alias)
            if spec is None:
                continue
            properties = {p.name: {"type": p.type if p.type in ("string", "integer", "number", "boolean") else "string",
                                   **({"description": _first_sentence(p.description, 80)} if p.description else {})}
                          for p in spec.params}
            if spec.buttons:
                properties["button"]["enum"] = spec.allowed_buttons((choices or {}).get(spec.alias))
            parameters: dict[str, Any] = {"type": "object", "properties": properties}
            if any(p.required for p in spec.params):
                parameters["required"] = [p.name for p in spec.params if p.required]
            tools.append({"type": "function", "function": {
                "name": spec.alias, "description": spec.description.rstrip(".") + "." if spec.description else spec.alias,
                "parameters": parameters}})
        return tools

    def native_prompt_section(self, aliases: list[str]) -> str:
        """Beim nativen Modus beschreibt das Chat-Template die Werkzeuge; hier nur die Verhaltensregeln."""
        if not any(normalize_name(a) in self.tools for a in aliases):
            return ""
        return "\n".join(["Tools:",
                          "- Use a tool only when you really need it; otherwise just answer normally.",
                          "- Never invent a result and never guess what a tool returns."])

    def native_call(self, name: str, arguments_json: str) -> tuple[str, dict]:
        """Nativer Aufruf -> (Marker-Text für Anzeige und Verlauf, benannte Argumente). Der Marker ist nur die
        lesbare Form; ausgeführt wird mit den echten Argumenten ('|' und ']' im Text würden den Marker sprengen)."""
        try:
            arguments = json.loads(arguments_json) if arguments_json.strip() else {}
        except ValueError:
            arguments = {}
        arguments = arguments if isinstance(arguments, dict) else {}
        spec = self.tools.get(normalize_name(name))
        order = [p.name for p in spec.params] if spec else list(arguments)
        values, gap = [], False
        for key in order:
            if key not in arguments:
                gap = True  # danach nur noch benannt schreiben, sonst verrutscht die Reihenfolge
                continue
            text = re.sub(r"[|\]\[\r\n]+", " ", str(arguments[key])).strip()
            values.append(f"{key}={text}" if gap else text)
        return "[[mcp:" + "|".join([normalize_name(name), *values]) + "]]", arguments

    def run(self, call: ToolCall, allowed: list[str], *, max_chars: int = 2000, timeout: float = 60.0,
            choices: dict[str, list[str]] | None = None) -> ToolOutcome:
        """Führt einen Aufruf aus. Fehler werden als Ergebnis (ok=False) zurückgegeben, damit das Modell sie sieht."""
        alias = normalize_name(call.name)
        spec = self.tools.get(alias)
        if spec is None or alias not in allowed:
            return ToolOutcome(call.name, False,
                               f"Unbekanntes Werkzeug '{call.name}'. Verfügbar: {', '.join(allowed) or 'keine'}.")
        if spec.macro:
            return self._run_macro(spec, allowed, max_chars, timeout)
        if spec.buttons:
            return self._run_button(spec, call, (choices or {}).get(spec.alias), max_chars, timeout)
        try:
            arguments = spec.resolve(call.args, call.arguments)
        except ToolArgError as exc:
            return ToolOutcome(spec.alias, False, str(exc))
        try:
            text, is_error = self.servers[spec.server].call_tool(spec.remote_name, arguments, timeout)
        except McpError as exc:
            return ToolOutcome(spec.alias, False, str(exc))
        text = neutralize(text) or "(leeres Ergebnis)"
        if len(text) > max_chars:
            text = text[:max_chars].rstrip() + " […gekürzt]"
        if is_error:
            return ToolOutcome(spec.alias, False, text)
        status = spec.status or f"{spec.alias} ausgeführt"
        return ToolOutcome(spec.alias, True, text, spec.result, status, spec.remember)

    def _run_macro(self, spec: ToolSpec, allowed: list[str], max_chars: int, timeout: float) -> ToolOutcome:
        """Spielt die Schritte nacheinander ab (Aufrufe der freigeschalteten Werkzeuge, Pausen). Der erste Fehler beendet das Makro."""
        lines: list[str] = []
        for number, step in enumerate(spec.macro, 1):
            if step[0] == "wait":
                time.sleep(step[1])
                lines.append(f"(wartet {step[1]:g} s)")
                continue
            outcome = self.run(ToolCall(step[1], step[2], "", 0, 0), list(self.tools), max_chars=max_chars, timeout=timeout)
            if not outcome.ok:
                return ToolOutcome(spec.alias, False, f"Schritt {number} ({step[1]}): {outcome.text}")
            lines.append(f"{step[1]}: {outcome.text}")
        return ToolOutcome(spec.alias, True, "\n".join(lines)[:max_chars], spec.result,
                           spec.status or f"{spec.alias} ausgeführt", spec.remember)

    def _run_button(self, spec: ToolSpec, call: ToolCall, only: list[str] | None, max_chars: int, timeout: float) -> ToolOutcome:
        """Drückt eine Taste eines Tastensatzes: das Argument "button" (Beschriftung, Hex oder Dezimalcode) gegen die erlaubten Tasten prüfen."""
        value = spec.bind(call.args) if call.arguments is None else {k: _unquote(v) for k, v in call.arguments.items()}
        asked = str(value.get("button") or "").strip()
        allowed = spec.allowed_buttons(only)
        if not asked:
            return ToolOutcome(spec.alias, False, f"Es fehlt: button. Richtiges Format: [[mcp:{spec.alias}|<button>]]. Erlaubt: {', '.join(allowed)}.")
        label = next((b for b in allowed if b.lower() == asked.lower()), None)
        if label is None:  # auch Hex/Dezimalcode einer erlaubten Taste
            code = self.button_codes(spec.server).get(asked.lower())
            label = next((b for b in allowed if code and self.button_codes(spec.server).get(b.lower()) == code), None)
        if label is None:
            return ToolOutcome(spec.alias, False, f"Unbekannte Taste '{asked}'. Erlaubt: {', '.join(allowed)}.")
        try:
            if label.lower() == "off" and (spec.server, "off") in self.remote:
                text, is_error = self.servers[spec.server].call_tool("off", {}, timeout)
            else:
                text, is_error = self.servers[spec.server].call_tool("send", {"code": self.button_codes(spec.server)[label.lower()]}, timeout)
        except McpError as exc:
            return ToolOutcome(spec.alias, False, str(exc))
        text = neutralize(text) or "(leeres Ergebnis)"
        if len(text) > max_chars:
            text = text[:max_chars].rstrip() + " […gekürzt]"
        if is_error:
            return ToolOutcome(spec.alias, False, text)
        return ToolOutcome(spec.alias, True, text, spec.result, spec.status or f"{spec.alias}: {label}", spec.remember)
