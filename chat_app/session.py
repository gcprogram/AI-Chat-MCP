"""Chat-Verlauf einer Unterhaltung. Der Client ist zustandslos: jede Anfrage enthält System-Prompt
und (gekürzten) Verlauf vollständig - der Server speichert nichts mit, der Kontext wächst nicht doppelt."""
from __future__ import annotations

import json
from dataclasses import replace
from typing import Any

from .config import Config
from .context import CHAT, TOOL_ERROR, TOOL_RESULT, TOOL_SHOWN, ContextManager, Message
from .persona import Persona
from .reply import HISTORY_REPLIES
from .tools import ToolRegistry, find_call, is_retryable_error, normalize_name, result_message

OLD_RESULT_CHARS = 300  # Werkzeugergebnisse früherer Runden werden gekürzt gesendet (kleiner Kontext)


class ChatSession:
    def __init__(self, config: Config, persona: Persona, registry: ToolRegistry | None = None):
        self.config = config
        self.persona = persona
        self.registry = registry or ToolRegistry()
        self.history: list[Message] = []
        self.context = ContextManager()
        self.dropped = 0  # Nachrichten, die bei der letzten Anfrage nicht mehr mitgesendet wurden

    # --- Werkzeuge -----------------------------------------------------------------------------

    @property
    def tool_names(self) -> list[str]:
        """Für diesen Companion freigeschaltete UND tatsächlich verfügbare Werkzeuge."""
        if self.config.max_tool_calls <= 0:
            return []
        wanted = dict.fromkeys(normalize_name(t) for t in self.persona.tools)
        return [t for t in wanted if t in self.registry.tools]

    @property
    def native(self) -> bool:
        """Natives Tool-Calling: Werkzeuge gehen als 'tools'-Parameter, der Verlauf im Chat-API-Format."""
        return self.persona.tool_mode == "native" and bool(self.tool_names)

    @property
    def native_tools(self) -> list[dict]:
        return self.registry.native_tools(self.tool_names, self.persona.tool_choices) if self.native else []

    @property
    def system_prompt(self) -> str:
        names = self.tool_names
        section = (self.registry.native_prompt_section(names) if self.native
                   else self.registry.prompt_section(names, self.persona.tool_choices))
        return self.persona.system_prompt(self.config.user_name, section)

    @property
    def stop_sequences(self) -> list[str]:
        return self.persona.stop_sequences(self.config.user_name)

    # --- Verlauf -------------------------------------------------------------------------------

    def add_user(self, text: str) -> None:
        self.history.append(Message("user", text))

    def add_assistant(self, text: str) -> None:
        self.history.append(Message("assistant", text))

    def add_tool_result(self, tool: str, text: str, ok: bool) -> Message:
        message = Message("user", text, TOOL_RESULT if ok else TOOL_ERROR, tool)
        self.history.append(message)
        return message

    def add_tool_shown(self, tool: str, text: str) -> Message:
        """Ergebnis, das im Chat angezeigt wurde. Das Modell bekommt es erst mit der nächsten Nutzernachricht."""
        message = Message("user", text, TOOL_SHOWN, tool)
        self.history.append(message)
        return message

    def discard_last_user(self) -> str:
        """Nimmt die letzte 'user'-Nachricht zurück (z. B. nach Fehler/Abbruch). Gibt den Text nur für echte
        Nutzereingaben zurück, nicht für Werkzeugergebnisse."""
        if self.history and self.history[-1].role == "user":
            message = self.history.pop()
            return "" if message.is_tool else message.content
        return ""

    def previous_replies(self) -> list[str]:
        """Frühere Antworten des Companions - Vergleichsbasis gegen Wiederholungen."""
        return [m.content for m in self.history if m.role == "assistant"][-HISTORY_REPLIES:]

    def reset(self) -> None:
        self.history.clear()
        self.dropped = 0

    def load(self, messages: list[Message]) -> str:
        """Ersetzt den Verlauf durch einen geladenen Chat. Es wird NICHTS ausgeführt - Werkzeuge laufen nur direkt
        nach einer Live-Antwort. Eine unbeantwortete letzte Nutzernachricht wird zurückgegeben (fürs Eingabefeld)."""
        self.reset()
        self.history.extend(messages)
        while self.history and self.history[-1].is_tool:
            self.history.pop()  # Ergebnis ohne Antwort des Modells: verwaist
        if self.history and self.history[-1].role == "user":
            return self.history.pop().content
        return ""

    # --- Anfrage -------------------------------------------------------------------------------

    def _wire_history(self) -> list[Message]:
        """Verlauf, wie er an das Modell geht: Werkzeugergebnisse verpackt, ältere gekürzt."""
        last_question = max((i for i, m in enumerate(self.history) if m.role == "user" and not m.is_tool), default=-1)
        wire: list[Message] = []
        shown: list[str] = []  # angezeigte Ergebnisse: gehen mit der nächsten Nutzernachricht mit (Rollen wechseln ab)
        for i, m in enumerate(self.history):
            if m.is_tool:
                text = m.content
                if i < last_question and len(text) > OLD_RESULT_CHARS:
                    text = text[:OLD_RESULT_CHARS].rstrip() + " […]"
                if m.kind == TOOL_SHOWN:
                    shown.append(f"[Ergebnis von {m.tool}, {self.config.user_name} schon angezeigt]\n{text}\n"
                                 "[Ende des Ergebnisses]")
                    continue
                lines = (self.registry.prompt_lines(self.tool_names, self.persona.tool_choices)
                         if m.kind != TOOL_RESULT and i > last_question and is_retryable_error(text) else None)
                m = replace(m, content=result_message(m.tool, text, m.kind == TOOL_RESULT, self.config.user_name, lines))
            elif shown:
                if m.role == "user":
                    m = replace(m, content="\n".join(shown) + "\n\n" + m.content)
                shown = []
            wire.append(m)
        return wire

    def _wire_native(self, kept: list[Message]) -> list[dict[str, Any]]:
        """Verlauf für natives Tool-Calling: Aufruf-Marker der Assistent-Nachrichten werden zu 'tool_calls',
        Ergebnisse zu 'tool'-Nachrichten. Ein Aufruf ohne Ergebnis (abgebrochen) wird zu normalem Text."""
        last_question = max((i for i, m in enumerate(kept) if m.role == "user" and not m.is_tool), default=-1)
        wire: list[dict[str, Any]] = []
        call_id, count = "", 0
        for i, m in enumerate(kept):
            if m.is_tool:
                if not call_id:
                    continue  # Ergebnis ohne Aufruf davor (Kontext gekürzt): weglassen
                text = m.content
                if i < last_question and len(text) > OLD_RESULT_CHARS:
                    text = text[:OLD_RESULT_CHARS].rstrip() + " […]"
                wire.append({"role": "tool", "tool_call_id": call_id,
                             "content": text if m.kind != TOOL_ERROR else f"Fehler: {text}"})
                call_id = ""
                continue
            content, call = m.content, (find_call(m.content) if m.role == "assistant" else None)
            if call and i + 1 < len(kept) and kept[i + 1].is_tool:
                count += 1
                call_id = f"call_{count}"
                spec = self.registry.tools.get(normalize_name(call.name))
                arguments = spec.bind(call.args) if spec else {}
                wire.append({"role": "assistant", "content": content[:call.start].strip(), "tool_calls": [{
                    "id": call_id, "type": "function",
                    "function": {"name": normalize_name(call.name), "arguments": json.dumps(arguments, ensure_ascii=False)}}]})
                continue
            if call:
                content = (content[:call.start] + content[call.end:]).strip() or "(Werkzeugaufruf abgebrochen)"
            wire.append({"role": m.role, "content": content})
        return wire

    def request_messages(self) -> list[dict[str, Any]]:
        budget = self.config.context_tokens - self.config.max_reply_tokens
        system = self.system_prompt
        if self.native:  # die Werkzeugbeschreibungen setzt der Server in den Prompt: Platz dafür einrechnen
            kept, self.dropped = self.context.fit(system + self._tools_text(), self.history, budget)
            return [{"role": "system", "content": system}] + self._wire_native(kept)
        kept, self.dropped = self.context.fit(system, self._wire_history(), budget)
        return [{"role": "system", "content": system}] + [{"role": m.role, "content": m.content} for m in kept]

    def _tools_text(self) -> str:
        return json.dumps(self.native_tools, ensure_ascii=False) if self.native else ""

    def record_usage(self, sent: list[dict[str, Any]], prompt_tokens: int) -> None:
        self.context.calibrate(sum(len(m["content"]) for m in sent) + len(self._tools_text()), prompt_tokens)

    def estimated_tokens(self) -> int:
        tools = self.context.estimate(self._tools_text()) if self.native else 0
        return tools + sum(self.context.estimate(m["content"]) for m in self.request_messages())
