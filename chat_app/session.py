"""Chat-Verlauf einer Unterhaltung. Der Client ist zustandslos: jede Anfrage enthält System-Prompt
und (gekürzten) Verlauf vollständig - der Server speichert nichts mit, der Kontext wächst nicht doppelt."""
from __future__ import annotations

from dataclasses import replace

from .config import Config
from .context import CHAT, TOOL_ERROR, TOOL_RESULT, TOOL_SHOWN, ContextManager, Message
from .persona import Persona
from .reply import HISTORY_REPLIES
from .tools import ToolRegistry, normalize_name, result_message

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
    def system_prompt(self) -> str:
        return self.persona.system_prompt(self.config.user_name, self.registry.prompt_section(self.tool_names))

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
                m = replace(m, content=result_message(m.tool, text, m.kind == TOOL_RESULT, self.config.user_name))
            elif shown:
                if m.role == "user":
                    m = replace(m, content="\n".join(shown) + "\n\n" + m.content)
                shown = []
            wire.append(m)
        return wire

    def request_messages(self) -> list[dict[str, str]]:
        budget = self.config.context_tokens - self.config.max_reply_tokens
        system = self.system_prompt
        kept, self.dropped = self.context.fit(system, self._wire_history(), budget)
        return [{"role": "system", "content": system}] + [{"role": m.role, "content": m.content} for m in kept]

    def record_usage(self, sent: list[dict[str, str]], prompt_tokens: int) -> None:
        self.context.calibrate(sum(len(m["content"]) for m in sent), prompt_tokens)

    def estimated_tokens(self) -> int:
        return sum(self.context.estimate(m["content"]) for m in self.request_messages())
