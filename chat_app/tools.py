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
from dataclasses import dataclass, field, replace
from pathlib import Path
from typing import Any

from .config import APP_DIR, MCP_CONFIG_PATH
from .mcp_client import McpError, McpServer

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


RESULT_MODEL, RESULT_SHOW, RESULT_DISCARD = "model", "show", "discard"
RESULT_MODES = (RESULT_MODEL, RESULT_SHOW, RESULT_DISCARD)


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
    result: str = RESULT_MODEL  # model: Modell antwortet | show: Klartext im Chat | discard: nur Statuszeile
    status: str = ""  # Statuszeile bei discard
    confirm: bool | None = None  # None = globale Einstellung "Vor jedem Werkzeugaufruf nachfragen"
    remember: bool = False  # Ergebnis bei show/discard dem Modell im Verlauf zeigen

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

    def prompt_line(self) -> str:
        description = self.description.rstrip(".") + "." if self.description else ""
        optional = [p.name for p in self.params if not p.required]
        note = f" Optional: {', '.join(optional)}." if optional else ""
        return f"- {self.alias}: {description}{note} Example: {self.example or self.usage}".replace("  ", " ")


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


def result_message(tool: str, text: str, ok: bool, user_name: str) -> str:
    """Text der Nachricht, mit der das Werkzeugergebnis an das Modell zurückgegeben wird."""
    if ok:
        return (f"[Ergebnis von {tool}]\n{text}\n[Ende des Ergebnisses]\n"
                f"Antworte {user_name} jetzt kurz und in deinen eigenen Worten anhand dieses Ergebnisses. "
                "Schreibe keinen neuen Werkzeugaufruf, außer dir fehlt noch eine Information.")
    return (f"[Fehler bei {tool}] {text}\n"
            f"Sage {user_name} kurz, dass es nicht geklappt hat, oder versuche den Aufruf im richtigen Format erneut.")


class ToolRegistry:
    """Alle Werkzeuge der konfigurierten MCP-Server. Pro Companion wird eine Auswahl freigeschaltet."""

    def __init__(self) -> None:
        self.servers: dict[str, McpServer] = {}
        self.tools: dict[str, ToolSpec] = {}  # was das Modell benutzen darf (Aliase)
        self.remote: dict[tuple[str, str], ToolSpec] = {}  # alle Werkzeuge der Server, auch unsichtbare
        self.errors: list[str] = []

    # --- Aufbau --------------------------------------------------------------------------------

    def add_server(self, name: str, server: McpServer, overrides: dict | None = None,
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
        for raw_name, entry in aliases.items():
            if isinstance(entry, dict) and entry.get("server") in skip_servers:
                continue
            alias = normalize_name(str(raw_name))
            try:
                self.tools[alias] = self._build_alias(alias, entry)
            except ValueError as exc:
                self.errors.append(f"Alias '{alias}': {exc}")
            else:
                added.append(alias)
        return added

    def _build_alias(self, alias: str, entry: Any) -> ToolSpec:
        if not isinstance(entry, dict):
            raise ValueError("Eintrag ist kein Objekt")
        server, tool = str(entry.get("server") or ""), str(entry.get("tool") or "")
        base = self.remote.get((server, tool))
        if base is None:
            raise ValueError(f"Werkzeug '{server}/{tool}' nicht gefunden (Server nicht gestartet?)")
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
        for name, entry in servers.items():
            if not isinstance(entry, dict) or entry.get("disabled") or not entry.get("command"):
                continue
            server = McpServer(str(entry["command"]), [str(a) for a in entry.get("args", [])],
                               {str(k): str(v) for k, v in (entry.get("env") or {}).items()},
                               str(entry.get("cwd") or base_dir), name)
            try:
                self.add_server(name, server, entry.get("tools"), expose_raw=entry.get("raw", True) is not False)
            except (McpError, OSError, ValueError, KeyError) as exc:
                log.warning("MCP-Server '%s' nicht verfügbar: %s", name, exc)
                self.errors.append(f"{name}: {exc}")
        disabled = frozenset(n for n, e in servers.items() if isinstance(e, dict) and e.get("disabled"))
        self.add_aliases(data.get("aliases"), disabled)

    def shutdown(self) -> None:
        for server in self.servers.values():
            server.close()

    # --- Nutzung -------------------------------------------------------------------------------

    def available(self) -> list[str]:
        return sorted(self.tools)

    def prompt_section(self, aliases: list[str]) -> str:
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
            *(spec.prompt_line() for spec in specs),
        ])

    # --- Natives Tool-Calling (Parameter "tools" der Chat-API) ------------------------------------

    def native_tools(self, aliases: list[str]) -> list[dict]:
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

    def run(self, call: ToolCall, allowed: list[str], *, max_chars: int = 2000, timeout: float = 60.0) -> ToolOutcome:
        """Führt einen Aufruf aus. Fehler werden als Ergebnis (ok=False) zurückgegeben, damit das Modell sie sieht."""
        alias = normalize_name(call.name)
        spec = self.tools.get(alias)
        if spec is None or alias not in allowed:
            return ToolOutcome(call.name, False,
                               f"Unbekanntes Werkzeug '{call.name}'. Verfügbar: {', '.join(allowed) or 'keine'}.")
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
