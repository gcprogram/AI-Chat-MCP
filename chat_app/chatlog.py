"""Chats speichern und laden.

Die Chat-Datei (Endung .aichat, Inhalt JSON) enthält den vollständigen Verlauf inkl. Werkzeugaufrufen/-ergebnissen; eine eigene
Endung, damit ein Chat nie versehentlich eine Companion-Datei (personas/*.json) überschreibt. Ältere Chats als .json lassen sich
weiter laden. Text (.txt) ist ein lesbarer Export ("Name: Text"), der sich auch wieder laden lässt (z. B. ältere Exporte).

Beim Laden wird nichts ausgeführt: gespeicherte Werkzeugaufrufe und ihre Ergebnisse sind reiner Verlauf."""
from __future__ import annotations

import json
import re
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path

from .context import CHAT, TOOL_ERROR, TOOL_RESULT, TOOL_SHOWN, Message

FORMAT_VERSION = 1
CHAT_EXTENSION = ".aichat"
MAX_FILE_BYTES = 10_000_000
TOOL_HEADER = "Werkzeug"  # Sprechername von Werkzeugergebnissen im Textformat


class ChatFileError(Exception):
    pass


@dataclass
class LoadedChat:
    messages: list[Message]
    persona: str = ""  # ID des Companions, mit dem gechattet wurde (nur JSON)


def save_json(path: Path | str, messages: list[Message], persona_id: str, user_name: str) -> None:
    data = {
        "format": "ai-chat-mcp", "version": FORMAT_VERSION, "saved": datetime.now().isoformat(timespec="seconds"),
        "persona": persona_id, "user_name": user_name,
        "messages": [{"role": m.role, "content": m.content,
                      **({"kind": m.kind, "tool": m.tool} if m.kind != CHAT else {})} for m in messages],
    }
    Path(path).write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def save_text(path: Path | str, messages: list[Message], user_name: str, persona_name: str) -> None:
    def speaker(m: Message) -> str:
        return TOOL_HEADER if m.is_tool else (user_name if m.role == "user" else persona_name)

    Path(path).write_text("\n\n".join(f"{speaker(m)}: {m.content}" for m in messages) + "\n", encoding="utf-8")


def default_filename(persona_id: str, suffix: str = CHAT_EXTENSION) -> str:
    return f"{persona_id or 'chat'}_{datetime.now():%Y-%m-%d_%H%M}{suffix}"


def _normalize(messages: list[Message]) -> list[Message]:
    """Leere Nachrichten entfernen, aufeinanderfolgende gleiche Sprecher (handgeschriebene Dateien) zusammenführen."""
    result: list[Message] = []
    for m in messages:
        if not m.content.strip():
            continue
        if result and not m.is_tool and not result[-1].is_tool and result[-1].role == m.role:
            result[-1] = Message(m.role, result[-1].content + "\n\n" + m.content.strip())
        else:
            result.append(Message(m.role, m.content.strip(), m.kind, m.tool))
    return result


def _load_json(text: str) -> LoadedChat:
    try:
        data = json.loads(text)
    except ValueError as exc:
        raise ChatFileError(f"Keine gültige JSON-Datei: {exc}") from exc
    raw = data.get("messages") if isinstance(data, dict) else data  # auch eine nackte Liste akzeptieren
    if not isinstance(raw, list):
        raise ChatFileError("Die Datei enthält keinen Chat (Feld 'messages' fehlt).")
    messages = []
    for item in raw:
        if not isinstance(item, dict):
            raise ChatFileError("Ungültiger Eintrag in 'messages'.")
        role = item.get("role")
        if role not in ("user", "assistant") or not isinstance(item.get("content"), str):
            raise ChatFileError("Ungültige Nachricht (role/content) in der Datei.")
        kind = item.get("kind") if item.get("kind") in (TOOL_RESULT, TOOL_ERROR, TOOL_SHOWN) and role == "user" else CHAT
        messages.append(Message(role, item["content"], kind, str(item.get("tool", "")) if kind != CHAT else ""))
    persona = data.get("persona", "") if isinstance(data, dict) else ""
    return LoadedChat(_normalize(messages), persona if isinstance(persona, str) else "")


def _load_text(text: str, user_names: set[str], assistant_names: set[str]) -> LoadedChat:
    names = {n: "user" for n in user_names} | {n: "assistant" for n in assistant_names} | {TOOL_HEADER: "tool"}
    header = re.compile(r"^(" + "|".join(re.escape(n) for n in sorted(names, key=len, reverse=True)) + r"):[ \t]?(.*)$")
    messages: list[Message] = []
    current: tuple[str, list[str]] | None = None

    def flush() -> None:
        if current:
            role, lines = current
            body = "\n".join(lines).strip()
            if role == "tool":
                messages.append(Message("user", body, TOOL_RESULT))
            else:
                messages.append(Message(role, body))

    for line in text.splitlines():
        match = header.match(line)
        if match:
            flush()
            current = (names[match[1]], [match[2]])
        elif current:
            current[1].append(line)
    flush()
    if not messages:
        raise ChatFileError("Kein Chat erkannt: erwartet Zeilen wie 'Name: Text' (Namen von Nutzer oder Companion).")
    return LoadedChat(_normalize(messages))


def load_chat(path: Path | str, user_names: set[str], assistant_names: set[str]) -> LoadedChat:
    path = Path(path)
    try:
        if path.stat().st_size > MAX_FILE_BYTES:
            raise ChatFileError("Datei ist zu groß für einen Chat.")
        text = path.read_text(encoding="utf-8-sig")
    except (OSError, UnicodeDecodeError) as exc:
        raise ChatFileError(f"Datei nicht lesbar: {exc}") from exc
    if path.suffix.lower() in (CHAT_EXTENSION, ".json") or text.lstrip().startswith(("{", "[")):
        return _load_json(text)
    return _load_text(text, user_names | {"Nutzer", "User"}, assistant_names)
