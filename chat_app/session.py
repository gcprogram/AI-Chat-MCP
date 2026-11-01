"""Chat-Verlauf einer Unterhaltung. Der Client ist zustandslos: jede Anfrage enthält System-Prompt
und (gekürzten) Verlauf vollständig - der Server speichert nichts mit, der Kontext wächst nicht doppelt."""
from __future__ import annotations

from .config import Config
from .context import ContextManager, Message
from .persona import Persona


class ChatSession:
    def __init__(self, config: Config, persona: Persona):
        self.config = config
        self.persona = persona
        self.history: list[Message] = []
        self.context = ContextManager()
        self.dropped = 0  # Nachrichten, die bei der letzten Anfrage nicht mehr mitgesendet wurden

    @property
    def system_prompt(self) -> str:
        return self.persona.system_prompt(self.config.user_name)

    @property
    def stop_sequences(self) -> list[str]:
        return self.persona.stop_sequences(self.config.user_name)

    def add_user(self, text: str) -> None:
        self.history.append(Message("user", text))

    def add_assistant(self, text: str) -> None:
        self.history.append(Message("assistant", text))

    def discard_last_user(self) -> str:
        """Nimmt die letzte Benutzernachricht zurück (z. B. nach Fehler/Abbruch) und gibt ihren Text zurück."""
        if self.history and self.history[-1].role == "user":
            return self.history.pop().content
        return ""

    def reset(self) -> None:
        self.history.clear()
        self.dropped = 0

    def request_messages(self) -> list[dict[str, str]]:
        budget = self.config.context_tokens - self.config.max_reply_tokens
        kept, self.dropped = self.context.fit(self.system_prompt, self.history, budget)
        return [{"role": "system", "content": self.system_prompt}] + [
            {"role": m.role, "content": m.content} for m in kept
        ]

    def record_usage(self, sent: list[dict[str, str]], prompt_tokens: int) -> None:
        self.context.calibrate(sum(len(m["content"]) for m in sent), prompt_tokens)

    def estimated_tokens(self) -> int:
        return sum(self.context.estimate(m["content"]) for m in self.request_messages())

    def transcript(self) -> list[tuple[str, str]]:
        return [(self.config.user_name if m.role == "user" else self.persona.name, m.content)
                for m in self.history]
