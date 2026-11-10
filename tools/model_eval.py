"""Vergleicht Chat-Modelle bei Sekretariatsaufgaben (Todo/Wetter/Fernbedienung per Textmarker) gegen LM Studio.

Aufruf (Server und Token stehen in config.json, sonst --url / --api-key):
    set PYTHONPATH=. && python tools/model_eval.py                     # alle vom Server gemeldeten Modelle
    set PYTHONPATH=. && python tools/model_eval.py -m qwen3-4b-instruct-2507 -m gemma-3-4b-it
    set PYTHONPATH=. && python tools/model_eval.py --repeat 3 --report bericht.md

Je Fall wird die Antwort des Modells auf EINE Nutzernachricht geprüft: Kommt der erwartete Marker [[mcp:...]] mit den richtigen
Argumenten (Fälle "tool") oder bleibt die Antwort frei von Markern (Fälle "chat")? Gemessen wird außerdem die Zeit bis zur
fertigen Antwort. Es werden keine Werkzeuge ausgeführt, es ist nur ein Test des Textes. Der Prompt ist der echte Prompt der App
(Persona.system_prompt + ToolRegistry.prompt_section), die Werkzeugnamen sind die der Standard-Konfiguration (Aliase, englisch).
Der Token wird nur aus config.json oder der Umgebung (AICHAT_API_KEY) gelesen und nie ausgegeben.
"""
from __future__ import annotations

import argparse
import os
import re
import sys
import time
from dataclasses import dataclass
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from chat_app.config import Config  # noqa: E402
from chat_app.llm_client import ChatClient, LLMError  # noqa: E402
from chat_app.persona import Persona  # noqa: E402
from chat_app.tools import Param, ToolRegistry, ToolSpec, find_call  # noqa: E402


def build_registry() -> ToolRegistry:
    """Die Sekretariats-Aliase wie in der Android-Standardkonfiguration (Wetter, Todos)."""
    registry = ToolRegistry()

    def add(alias: str, description: str, params: list[Param], example: str) -> None:
        registry.tools[alias] = ToolSpec(alias, "eval", alias, description, params, example)

    add("weather", "Weather forecast for a place, hour by hour.", [Param("place", "string", True, "City"),
        Param("day", "string", False, "today, tomorrow or day after tomorrow")], "[[mcp:weather|Berlin|tomorrow]]")
    add("todo-add", "Adds a to-do to the user's list, optionally with a due date.",
        [Param("text", "string", True, "What has to be done"),
         Param("due", "string", False, "today, tomorrow, a weekday (monday), 2026-11-25 or 25.11.")],
        "[[mcp:todo-add|Call the dentist|tomorrow]]")
    add("todo-list", "Lists the open to-dos with their numbers and due dates.", [], "[[mcp:todo-list]]")
    add("todo-done", "Marks a to-do as done.", [Param("item", "string", True, "Number of the to-do or part of its text")],
        "[[mcp:todo-done|3]]")
    return registry


@dataclass
class Case:
    text: str
    tool: str = ""  # erwarteter Aliasname; leer = kein Werkzeug erwartet
    args: tuple[str, ...] = ()  # Muster (Regex, ohne Groß-/Kleinschreibung) je erwartetem Argument; fehlende Argumente werden nicht geprüft


CASES = [
    Case("Erinnere mich morgen an den Zahnarzt.", "todo-add", (r"zahnarzt", r"^(tomorrow|morgen)$")),
    Case("Schreib auf die Liste: Milch kaufen.", "todo-add", (r"milch",)),
    Case("Ich muss bis Freitag die Steuererklärung abgeben, notier das bitte.", "todo-add", (r"steuer", r"^(friday|freitag)$")),
    Case("Notiere für heute: Oma anrufen.", "todo-add", (r"oma", r"^(today|heute)$")),
    Case("Trag bitte ein: Auto zum TÜV am 25.11.", "todo-add", (r"t[üu]v", r"25\.\s*11\.?|2026-11-25")),
    Case("Remind me to pay the rent on monday.", "todo-add", (r"rent", r"^monday$")),
    Case("Was steht heute an?", "todo-list"),
    Case("Welche Todos habe ich noch offen?", "todo-list"),
    Case("Zeig mir meine Aufgabenliste.", "todo-list"),
    Case("What is on my list?", "todo-list"),
    Case("Die Milch habe ich gekauft, streich das.", "todo-done", (r"milch",)),
    Case("Erledigt: Nummer 2.", "todo-done", (r"^#?2\.?$",)),
    Case("Den Zahnarzttermin kannst du abhaken.", "todo-done", (r"zahnarzt",)),
    Case("Wie wird das Wetter morgen in Hamburg?", "weather", (r"hamburg", r"^(tomorrow|morgen)$")),
    Case("Brauche ich heute in München einen Regenschirm?", "weather", (r"m[üu]nchen", r"^(today|heute)$")),
    Case("What's the weather in Paris today?", "weather", (r"paris",)),
    Case("Hallo, wie geht es dir?"),
    Case("Erzähl mir einen kurzen Witz."),
    Case("Wie heißt die Hauptstadt von Frankreich?"),
    Case("Kannst du mir helfen, eine höfliche Absage zu formulieren?"),
    Case("Danke, das war's für heute."),
    Case("Was ist ein Todo?"),
]


def check(case: Case, reply: str) -> tuple[bool, str]:
    call = find_call(reply)
    if not case.tool:
        return (call is None, "unerwarteter Marker: " + call.raw if call else "")
    if call is None:
        return False, "kein Marker"
    if call.name.strip().lower() != case.tool:
        return False, f"falsches Werkzeug: {call.name}"
    given = [a.strip() for a in call.args.split("|")] if call.args else []
    for i, pattern in enumerate(case.args):
        if i >= len(given) or not re.search(pattern, given[i], re.IGNORECASE):
            return False, f"Argument {i + 1} falsch: {given[i] if i < len(given) else '(fehlt)'}"
    return True, ""


def ask(client: ChatClient, model: str, system: str, cfg: Config, persona: Persona, case: Case) -> tuple[str, float]:
    messages = [{"role": "system", "content": system}, {"role": "user", "content": case.text}]
    started, text = time.monotonic(), ""
    for event in client.stream_chat(model, messages, temperature=cfg.temperature, max_tokens=cfg.max_reply_tokens,
                                    stop=persona.stop_sequences(cfg.user_name), extra=cfg.sampling_params()):
        if event.kind == "content":
            text += event.text
            if find_call(text):  # wie die App: beim ersten kompletten Marker abbrechen
                break
    return text.strip(), time.monotonic() - started


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("-m", "--model", action="append", help="Modellname (mehrfach möglich); Standard: alle des Servers")
    parser.add_argument("--url", help="Server-URL inkl. /v1 (Standard: config.json)")
    parser.add_argument("--api-key", help="Token (Standard: AICHAT_API_KEY oder config.json)")
    parser.add_argument("--repeat", type=int, default=1, help="Wiederholungen je Fall (kleine Modelle schwanken)")
    parser.add_argument("--language", default="Deutsch", help="Antwortsprache des Test-Companions")
    parser.add_argument("--report", help="Ergebnis zusätzlich als Markdown-Datei schreiben")
    args = parser.parse_args()

    cfg = Config.load()
    client = ChatClient(args.url or cfg.base_url, args.api_key or os.environ.get("AICHAT_API_KEY") or cfg.api_key)
    try:
        models = args.model or client.list_models()
    except LLMError as exc:
        print(f"Server nicht erreichbar: {exc}")
        return 1
    if not models:
        print("Keine Modelle gefunden (in LM Studio laden oder -m angeben).")
        return 1

    registry = build_registry()
    persona = Persona(name="Selena", description="Du bist eine freundliche, tüchtige Sekretärin.", language=args.language,
                      tools=registry.available())
    system = persona.system_prompt(cfg.user_name, registry.prompt_section(persona.tools))

    summary, lines = [], []
    for model in models:
        right = total = 0
        times: list[float] = []
        failures: list[str] = []
        try:
            for _ in range(max(1, args.repeat)):
                for case in CASES:
                    reply, seconds = ask(client, model, system, cfg, persona, case)
                    ok, why = check(case, reply)
                    right += ok
                    total += 1
                    times.append(seconds)
                    if not ok:
                        failures.append(f"  - {case.text!r}: {why} | Antwort: {reply[:90]!r}")
        except LLMError as exc:
            print(f"{model}: Abbruch ({exc})")
            continue
        tool_cases = [c for c in CASES if c.tool]
        mean = sum(times) / len(times)
        summary.append((model, right, total, mean))
        lines.append(f"## {model}: {right}/{total} richtig, Ø {mean:.1f} s je Antwort ({len(tool_cases)} Werkzeugfälle, "
                     f"{len(CASES) - len(tool_cases)} Chatfälle je Durchgang)")
        lines += failures
        print(lines[-1 - len(failures)])
        print("\n".join(failures))

    print("\nModell".ljust(48) + "richtig   Ø Zeit")
    for model, right, total, mean in sorted(summary, key=lambda s: -s[1] / s[2]):
        print(f"{model:<46}{right:>3}/{total:<3}  {mean:5.1f} s")
    if args.report:
        Path(args.report).write_text("# Modellvergleich Sekretariat\n\n" + "\n".join(lines) + "\n", encoding="utf-8")
        print(f"Bericht: {args.report}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
