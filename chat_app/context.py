"""Kontext-Verwaltung für kleine lokale Modelle (z. B. 8192 Token).

Aktuelle Strategie: System-Prompt immer behalten, älteste Nachrichten (in ganzen Runden) weglassen,
bis die Anfrage samt Antwort-Reserve ins Fenster passt. Die Token-Schätzung kalibriert sich an den
echten Werten, die der Server zurückmeldet. Hier kommt später die Zusammenfassung alter Runden hinein."""
from __future__ import annotations

from dataclasses import dataclass

CHAT, TOOL_RESULT, TOOL_ERROR = "chat", "tool_result", "tool_error"
TOOL_SHOWN = "tool_shown"  # Ergebnis, das direkt im Chat angezeigt wurde (das Modell antwortet nicht darauf)


@dataclass(frozen=True)
class Message:
    role: str  # "user" | "assistant"
    content: str
    kind: str = CHAT  # Werkzeugergebnisse gehen als 'user'-Nachricht zum Modell, sind aber keine Nutzereingabe
    tool: str = ""  # Name des Werkzeugs bei kind != CHAT

    @property
    def is_tool(self) -> bool:
        return self.kind in (TOOL_RESULT, TOOL_ERROR, TOOL_SHOWN)


class ContextManager:
    def __init__(self) -> None:
        self.chars_per_token = 3.0  # konservativ für Deutsch/Englisch

    def estimate(self, text: str) -> int:
        return int(len(text) / self.chars_per_token) + 4  # +4: Rollen-/Template-Overhead pro Nachricht

    def calibrate(self, chars: int, prompt_tokens: int) -> None:
        """Gemessenes Zeichen/Token-Verhältnis des Servers einrechnen (geglättet)."""
        if chars > 0 and prompt_tokens > 0:
            observed = max(1.5, min(6.0, chars / prompt_tokens))
            self.chars_per_token = 0.5 * self.chars_per_token + 0.5 * observed

    def fit(self, system: str, history: list[Message], budget_tokens: int) -> tuple[list[Message], int]:
        """Neueste Nachrichten, die in das Budget passen (mind. die letzte). Liefert (Nachrichten, Anzahl entfernt)."""
        remaining = budget_tokens - self.estimate(system)
        kept: list[Message] = []
        for message in reversed(history):
            cost = self.estimate(message.content)
            if kept and cost > remaining:
                break
            kept.append(message)
            remaining -= cost
        kept.reverse()
        # Verlauf muss mit einer echten Nutzernachricht beginnen (kein Assistent, kein verwaistes Werkzeugergebnis)
        while len(kept) > 1 and (kept[0].role != "user" or kept[0].is_tool):
            kept.pop(0)
        return kept, len(history) - len(kept)
