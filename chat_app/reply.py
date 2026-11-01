"""Bereinigung der Modellantwort - Gegenmittel für kleine Modelle (z. B. 4B), die sich wiederholen.

Beobachtet im Beispiel-Chat: dieselben Sätze am Ende jeder Antwort, Floskeln wie "Danke für deine Frage" in jeder
Antwort, durchsickernde Markup-/Rollenfragmente ("<h1>User:</h1>"), abgebrochene Sätze. Besonders schädlich:
Das Modell sieht seine eigenen Wiederholungen im Verlauf und macht sie nach. Deshalb wird die Antwort bereinigt,
BEVOR sie im Verlauf landet (der Verlauf bleibt sauber), und eine erkennbare Endlosschleife wird schon beim
Streamen abgebrochen (spart Tokens)."""
from __future__ import annotations

import re
from dataclasses import dataclass, field
from difflib import SequenceMatcher

from .tools import CALL_RE, ToolCall, find_call

SIMILARITY = 0.8  # Wortfolgen-Ähnlichkeit, ab der ein Satz als Wiederholung gilt
MIN_WORDS = 4  # kürzere Sätze ("Ja, genau.") zählen nie als Wiederholung
HISTORY_REPLIES = 6  # so viele frühere Antworten werden zum Vergleich herangezogen

_SPLIT = re.compile(r"((?<=[.!?…])[ \t]+|\n+)")
_MARKUP = re.compile(r"</?(?:h[1-6]|div|span|p|br|ul|ol|li|html|body)\b[^<>]{0,40}>"
                     r"|<\|[^<>|]{1,40}\|>|</?(?:start|end)_of_turn>", re.IGNORECASE)
_LOOP = re.compile(r"(.{6,60}?)(?:\s*\1){2,}", re.DOTALL)  # derselbe Textbaustein mindestens dreimal hintereinander
_SENTENCE_END = re.compile(r"[.!?…][\"»“)]*(?=\s)")
_TRIGGER = re.compile(r"[.!?…\n<:\]]")


@dataclass
class Reply:
    text: str  # bereinigter Text für Verlauf und Anzeige; enthält den Werkzeugaufruf, falls vorhanden
    call: ToolCall | None = None
    notes: list[str] = field(default_factory=list)  # was bereinigt wurde (für die Statuszeile)


def _role_header(user_name: str, persona_name: str) -> re.Pattern:
    names = "|".join(re.escape(n) for n in {"User", "Nutzer", "Human", "Assistant", user_name, persona_name} if n)
    return re.compile(rf"(?:^|\n|(?<=[.!?…]\s))(?:{names})[ \t]*:")


def _sentences(text: str) -> list[tuple[str, str]]:
    """(Satz, folgendes Trennzeichen)-Paare; Zeilenumbrüche bleiben erhalten."""
    parts = _SPLIT.split(text)
    pairs = []
    for i in range(0, len(parts), 2):
        sep = parts[i + 1] if i + 1 < len(parts) else ""
        if parts[i].strip() or sep:
            pairs.append((parts[i], sep))
    return pairs


def _words(sentence: str) -> list[str]:
    return re.sub(r"[^\w\s]", " ", sentence.lower()).split()


def _similar(a: list[str], b: list[str]) -> bool:
    if a == b:
        return True
    if abs(len(a) - len(b)) > max(len(a), len(b)) * 0.5:
        return False
    return SequenceMatcher(None, a, b, autojunk=False).ratio() >= SIMILARITY


def _pool(previous: list[str]) -> list[list[str]]:
    pool = []
    for text in previous[-HISTORY_REPLIES:]:
        for sentence, _ in _sentences(CALL_RE.sub("", text)):
            words = _words(sentence)
            if len(words) >= MIN_WORDS:
                pool.append(words)
    return pool


def cut_junk(text: str, user_name: str, persona_name: str) -> tuple[str, bool]:
    """Schneidet am ersten Markup-/Rollenfragment ab; danach ist das Modell aus der Spur."""
    positions = [m.start() for m in (_MARKUP.search(text), _role_header(user_name, persona_name).search(text)) if m]
    if not positions:
        return text, False
    return text[:min(positions)], True


def trim_dangling(text: str, min_words: int = 3) -> str:
    """Entfernt einen abgebrochenen letzten Satz (Token-Limit, Abbruch), wenn davor ein ganzer Satz steht."""
    text = text.rstrip()
    if not text or not (text[-1].isalnum() or text[-1] in ",;:-–("):
        return text
    ends = list(_SENTENCE_END.finditer(text))
    if ends and len(text[ends[-1].end():].split()) >= min_words:
        return text[:ends[-1].end()].rstrip()
    return text


def remove_repeats(text: str, pool: list[list[str]]) -> tuple[str, int, bool]:
    """Streicht Sätze, die schon vorher in dieser Antwort (-> looped) oder in früheren Antworten standen."""
    kept: list[tuple[str, str]] = []
    seen: list[list[str]] = []
    removed, looped = 0, False
    for sentence, sep in _sentences(text):
        words = _words(sentence)
        if len(words) >= MIN_WORDS:
            if any(_similar(words, s) for s in seen):
                removed, looped = removed + 1, True
                continue
            if any(_similar(words, s) for s in pool):
                removed += 1
                continue
            seen.append(words)
        kept.append((sentence, sep))
    return "".join(s + sep for s, sep in kept).strip(), removed, looped


def finalize_reply(raw: str, *, user_name: str, persona_name: str, previous: list[str] | None = None,
                   tool_names: list[str] | None = None) -> Reply:
    """Vollständige Bereinigung einer fertigen (oder abgebrochenen) Antwort."""
    notes: list[str] = []
    text, call = raw, None
    if tool_names:
        call = find_call(text)
        if call:
            if text[call.end:].strip():
                notes.append("Text nach dem Werkzeugaufruf verworfen")
            text = text[:call.start]
    text = re.sub(rf"^\s*{re.escape(persona_name)}[ \t]*:[ \t]*", "", text)  # eigener Namens-Präfix
    text, junk = cut_junk(text, user_name, persona_name)
    if junk:
        notes.append("Fremdtext/Markup abgeschnitten")
        call = None  # Aufruf nach dem Aussetzer ist nicht vertrauenswürdig
        text = trim_dangling(text, min_words=1)
    collapsed = _LOOP.sub(r"\1", text)
    if collapsed != text:
        notes.append("Endlosschleife gekürzt")
    cleaned, removed, _ = remove_repeats(collapsed, _pool(previous or []))
    if removed:
        notes.append(f"{removed} wiederholte Sätze entfernt")
    if not cleaned.strip():
        cleaned = collapsed.strip()  # lieber eine wiederholte Antwort als keine
    if call is None:
        cleaned = trim_dangling(cleaned)
    final = (cleaned + (" " + call.raw if call else "")).strip()
    return Reply(final, call, notes)


class StreamGuard:
    """Beobachtet den Stream und meldet, wann die Generierung abgebrochen werden sollte:
    'call' (Werkzeugaufruf komplett), 'junk' (Markup/Rollenzeile), 'repeat' (Satz wiederholt), 'loop' (Baustein)."""

    def __init__(self, user_name: str, persona_name: str, tool_names: list[str] | None = None):
        self.user_name, self.persona_name, self.tools = user_name, persona_name, bool(tool_names)
        self.text = ""
        self._checked = 0

    def feed(self, chunk: str) -> str | None:
        self.text += chunk
        if not _TRIGGER.search(chunk) and len(self.text) - self._checked < 80:
            return None
        self._checked = len(self.text)
        if self.tools and find_call(self.text):
            return "call"
        text = CALL_RE.sub("", self.text)
        if cut_junk(text, self.user_name, self.persona_name)[1]:
            return "junk"
        if _LOOP.search(text[-400:]):
            return "loop"
        seen: list[list[str]] = []
        for sentence, sep in _sentences(text):
            if not sep:  # letzter Satz ist noch nicht fertig
                break
            words = _words(sentence)
            if len(words) >= MIN_WORDS:
                if any(_similar(words, s) for s in seen):
                    return "repeat"
                seen.append(words)
        return None
