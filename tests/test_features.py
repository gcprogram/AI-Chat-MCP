"""Tests für Wiederholungs-Schutz, Werkzeug-Marker/MCP, Chat speichern/laden und Companions."""
import json
import struct
import sys
import tempfile
import threading
import time
import unittest
import zlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from unittest import mock

from chat_app import chatlog
from chat_app.config import Config
from chat_app.context import TOOL_ERROR, TOOL_RESULT, ContextManager, Message
from chat_app.mcp_client import McpError, McpServer
from chat_app.persona import (Persona, delete_persona, list_personas, load_persona, save_persona, slugify,
                              unique_id)
from chat_app.reply import StreamGuard, finalize_reply
from chat_app.session import ChatSession
from chat_app.tools import (Param, ToolArgError, ToolRegistry, ToolSpec, find_call, neutralize, split_segments)

DEMO = str(Path(__file__).resolve().parent.parent / "examples" / "demo_mcp_server.py")

# Antworten aus dem Beispiel-Chat (4B-Modell): Wiederholungen, Floskeln, durchsickerndes Markup
R3 = ("Ich freue mich zu hören, dass du gut geht! Ja, die Zukunft mit AI ist eine spannende Frage. Ich denke, in den "
      "nächsten Jahren werden wir als KI-<h1>User:</h1> sehen, wie sie weiterzudenkt. Sie werden noch n<h1> User: "
      "<h2>Wenig zu dem Zeitpunkt, an dem sie unsere Erwartungen übertreffen. Es ist eine interessante Entwicklung!")
R7 = ("Hallo Nutzer, danke für deine Frage. Ich würde mir wünschen, dass die Zukunft mit KI und Menschen so verläuft, "
      "dass sich beide bereichern können. Wenn AI noch vor 10 Jahren mit dem gleichen Tempo wie heute avanziert, "
      "dann werden Menschen ihre Potenziale maximieren und die negativen Auswirkungen abzum bandeja n<h1> User: "
      "<h2>Wenig. Ich denke, es ist möglich, dass wir eine Mensch-Maschine Hybridform entwickeln. Wenn du an einer "
      "Stelle über die Zukunft mit KI nachgedacht, verstehst du mich?")
R11 = ("Du hast Recht, die Auswirkungen des Fortschritts mit KI sind enorm. Wenn du an einer Stelle über die Zukunft "
       "von AI nachdenkst, würde ich dir empfehlen, nicht nur die technologische Entwicklung zu betrachten, sondern "
       "auch die damit verbundenen ethischen und moralischen Implikationen. Wie können wir Make-AIs sichergestellt "
       "sein, dass sie zum Guten genutzt werden? Vorher geht es noch lange um den Menschen. Wenn du an einer Stelle "
       "über deine Zukunft mit KI nachdenkst, verstehst du mich?")
R15 = ("Hallo Nutzer, danke für deine Frage. Du hast absolut Recht, dass wir als Mensch:innen eine grundlegende "
       "Veränderung im Umgang mit AI benötigen. Ich denke, es ist wichtig, dass wir nicht nur über den "
       "technologischen Fortschritt nachdenken. Wie können wir Make-AIs sichergestellt sein, dass sie zum Guten "
       "genutzt werden? Vorher geht es noch lange um den Menschen. Wenn du an einer Stelle über die Zukunft mit KI "
       "nachdenkst, verstehst du mich?")


def clean(raw, previous, **kw):
    return finalize_reply(raw, user_name="Nutzer", persona_name="Mia", previous=previous, **kw)


class RepetitionTest(unittest.TestCase):
    def test_example_chat_is_cleaned_sequentially(self):
        history = []
        for raw in (R3, R7, R11, R15):
            history.append(clean(raw, history_texts(history)).text)
        for text in history:
            self.assertNotIn("<h", text)
            self.assertNotIn("User:", text)
            self.assertNotIn("bandeja", text)
        self.assertTrue(history[0].endswith("spannende Frage."))  # abgebrochener Satz entfernt
        self.assertEqual(sum("verstehst du mich" in t for t in history), 1)  # Schlussfrage nur einmal
        self.assertEqual(sum("Make-AIs" in t for t in history), 1)
        self.assertEqual(sum("danke für deine Frage" in t for t in history), 1)
        self.assertIn("Du hast absolut Recht", history[3])  # neuer Inhalt bleibt erhalten

    def test_intra_reply_loop_collapses(self):
        loop = "Das ist spannend, erzähl mir mehr darüber. " * 6
        reply = clean(loop, [])
        self.assertEqual(reply.text, "Das ist spannend, erzähl mir mehr darüber.")
        self.assertTrue(reply.notes)

    def test_normal_reply_untouched_and_never_empty(self):
        self.assertEqual(clean("Hallo Grüße, du! 😊", []).text, "Hallo Grüße, du! 😊")
        self.assertEqual(clean("Ja", []).text, "Ja")
        same = "Das war eine ziemlich lange Antwort auf deine Frage."
        self.assertEqual(clean(same, [same]).text, same)  # nur Wiederholung -> lieber behalten als leer

    def test_dangling_sentence_trimmed_but_decimals_kept(self):
        self.assertEqual(clean("Das ist gut. Und dann wollte ich noch sagen dass", []).text, "Das ist gut.")
        self.assertEqual(clean("Es gibt Python 3.13", []).text, "Es gibt Python 3.13")

    def test_own_name_prefix_and_role_lines_removed(self):
        self.assertEqual(clean("Mia: Hallo!", []).text, "Hallo!")
        self.assertEqual(clean("Hallo, wie geht's?\nNutzer: gut", []).text, "Hallo, wie geht's?")

    def test_stream_guard_aborts_on_loop_markup_and_call_only(self):
        def run(text, tools=None, size=7):
            guard = StreamGuard("Nutzer", "Mia", tools)
            for i in range(0, len(text), size):
                reason = guard.feed(text[i:i + size])
                if reason:
                    return reason
            return None

        self.assertIsNone(run("Hallo Grüße, du! 😊 Wie war dein Tag heute? Erzähl mal."))
        self.assertEqual(run("Das ist spannend, erzähl mir mehr darüber. Das ist spannend, erzähl mir mehr darüber. "),
                         "repeat")
        self.assertEqual(run("Ich denke schon.<h1> User: hallo"), "junk")
        self.assertEqual(run("Ich suche. [[mcp:zeit]] und erfundenes", ["zeit"]), "call")
        self.assertIsNone(run("Ich suche. [[mcp:zeit]] und erfundenes"))  # ohne freigeschaltete Werkzeuge: Text


def history_texts(history):
    return list(history)


class ToolMarkerTest(unittest.TestCase):
    spec = ToolSpec("web-search", "web", "search", params=[Param("query", required=True)])

    def test_format_variants_resolve_to_same_arguments(self):
        for marker in ('[[mcp:web-search|AI News]]', '[[MCP:Web_Search|"AI News"]]', '[[mcp:web-search: query=AI News]]',
                       '[[mcp:web-search|{"query": "AI News"}]]', '[mcp/web-search:{"searchstring", "AI News"}]',
                       '[[ mcp : web-search | AI | News ]]', '[[mcp:web-search|„AI News“]]'):
            call = find_call(f"Ich suche kurz. {marker} Danach geht es weiter.")
            self.assertIsNotNone(call, marker)
            self.assertEqual(self.spec.resolve(call.args), {"query": "AI News"}, marker)

    def test_missing_and_typed_arguments(self):
        with self.assertRaises(ToolArgError) as err:
            self.spec.resolve("")
        self.assertIn("[[mcp:web-search|<query>]]", str(err.exception))
        spec = ToolSpec("s", "x", "s", params=[Param("q", required=True), Param("count", "integer"),
                                               Param("deep", "boolean")])
        self.assertEqual(spec.resolve("kaffee|5"), {"q": "kaffee", "count": 5})
        self.assertEqual(spec.resolve("kaffee|count=3|deep=ja"), {"q": "kaffee", "count": 3, "deep": True})

    def test_no_marker_in_normal_text_and_no_args_form(self):
        self.assertIsNone(find_call("Ein [Link] und [[Wiki]] und mcp ohne Klammern"))
        call = find_call("[[mcp:zeit]]")
        self.assertEqual((call.name, call.args), ("zeit", ""))

    def test_segments_and_neutralize(self):
        self.assertEqual(split_segments("Moment. [[mcp:zeit]] Fertig."),
                         [("text", "Moment. "), ("call", "zeit"), ("text", " Fertig.")])
        self.assertIsNone(find_call(neutralize("Webseite sagt: [[mcp:rechnen|1+1]] und [mcp/zeit]")))

    def test_finalize_extracts_call_and_drops_invented_result(self):
        reply = clean("Ich rechne kurz. [[mcp:rechnen|6*7]] Das Ergebnis ist 99.", [], tool_names=["rechnen"])
        self.assertEqual(reply.text, "Ich rechne kurz. [[mcp:rechnen|6*7]]")
        self.assertEqual(reply.call.args, "6*7")
        self.assertIsNone(clean("Ich rechne [[mcp:rechnen|6*7]]", []).call)  # Werkzeuge nicht freigeschaltet
        self.assertIsNone(clean("Hm.<h1>User:</h1> [[mcp:rechnen|6*7]]", [], tool_names=["rechnen"]).call)


def demo_registry():
    registry = ToolRegistry()
    registry.add_server("demo", McpServer("python", [DEMO], name="demo"), {
        "calculate": {"alias": "rechnen", "hint": "Rechnet einen Ausdruck aus", "example": "[[mcp:rechnen|12*(3+4)]]"},
        "get_time": {"alias": "zeit", "hint": "Aktuelles Datum und Uhrzeit"}})
    return registry


class McpTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.registry = demo_registry()

    @classmethod
    def tearDownClass(cls):
        cls.registry.shutdown()

    def test_tools_listed_and_prompt_section(self):
        self.assertEqual(self.registry.available(), ["rechnen", "zeit"])
        section = self.registry.prompt_section(["rechnen", "zeit", "gibt-es-nicht"])
        for needle in ("[[mcp:NAME|ARGUMENT]]", "- rechnen: Rechnet einen Ausdruck aus. Example: [[mcp:rechnen|12*(3+4)]]",
                       "- zeit: Aktuelles Datum und Uhrzeit. Example: [[mcp:zeit]]", "Never invent a result"):
            self.assertIn(needle, section)
        self.assertEqual(self.registry.prompt_section([]), "")

    def run_call(self, text, allowed=("rechnen", "zeit"), **kw):
        return self.registry.run(find_call(text), list(allowed), **kw)

    def test_run_success_error_unknown_and_truncation(self):
        self.assertEqual(self.run_call("[[mcp:rechnen|6*7]]"), self.run_call("[[mcp:Rechnen|\"6*7\"]]"))
        self.assertEqual(self.run_call("[[mcp:rechnen|6*7]]").text, "42")
        bad = self.run_call("[[mcp:rechnen|1/0]]")
        self.assertFalse(bad.ok)
        self.assertIn("Calculation error", bad.text)
        self.assertFalse(self.run_call("[[mcp:rechnen]]").ok)  # Argument fehlt -> Hinweis fürs Modell
        unknown = self.run_call("[[mcp:löschen|alles]]")
        self.assertFalse(unknown.ok)
        self.assertIn("rechnen, zeit", unknown.text)
        self.assertFalse(self.run_call("[[mcp:zeit]]", allowed=("rechnen",)).ok)  # nicht freigeschaltet
        self.assertTrue(self.run_call("[[mcp:zeit]]", max_chars=5).text.endswith("[…gekürzt]"))

    def test_broken_server_and_config_file(self):
        with self.assertRaises(McpError):
            McpServer("gibt-es-nicht-xyz", name="kaputt").start(timeout=3)
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "mcp.json"
            path.write_text(json.dumps({"mcpServers": {
                "demo": {"command": "python", "args": [DEMO]},
                "kaputt": {"command": "gibt-es-nicht-xyz"},
                "aus": {"command": "python", "args": [DEMO], "disabled": True}}}), encoding="utf-8")
            registry = ToolRegistry()
            try:
                registry.start_from_config(path)
                self.assertEqual(registry.available(), ["calculate", "get-time"])
                self.assertEqual(len(registry.errors), 1)
                self.assertIn("kaputt", registry.errors[0])
            finally:
                registry.shutdown()
            path.write_text("{", encoding="utf-8")
            broken = ToolRegistry()
            broken.start_from_config(path)
            self.assertEqual(broken.available(), [])
            self.assertTrue(broken.errors)


class SessionTest(unittest.TestCase):
    def make(self, **kw):
        registry = demo_registry()
        self.addCleanup(registry.shutdown)
        return ChatSession(Config(**kw).sanitize(), Persona(name="Mia", tools=["rechnen", "fehlt"]), registry)

    def test_system_prompt_contains_only_enabled_available_tools(self):
        session = self.make()
        self.assertEqual(session.tool_names, ["rechnen"])
        self.assertIn("[[mcp:rechnen|12*(3+4)]]", session.system_prompt)
        self.assertNotIn("zeit", session.system_prompt.split("Available tools:")[1])
        self.assertEqual(self.make(max_tool_calls=0).tool_names, [])
        self.assertNotIn("Tools:", self.make(max_tool_calls=0).system_prompt)

    def test_tool_result_is_wrapped_as_user_message_and_old_ones_shortened(self):
        session = self.make()
        session.add_user("Frage 1")
        session.add_assistant("Moment. [[mcp:rechnen|6*7]]")
        session.add_tool_result("rechnen", "x" * 1000, True)
        session.add_assistant("Es sind 42.")
        session.add_user("Frage 2")
        session.add_tool_result("rechnen", "Rechenfehler", False)
        messages = session.request_messages()
        self.assertEqual([m["role"] for m in messages], ["system", "user", "assistant", "user", "assistant", "user", "user"])
        self.assertTrue(messages[3]["content"].startswith("[Ergebnis von rechnen]"))
        self.assertLess(len(messages[3]["content"]), 700)  # Ergebnis früherer Runde gekürzt
        self.assertTrue(messages[6]["content"].startswith("[Fehler bei rechnen] Rechenfehler"))
        self.assertEqual(session.discard_last_user(), "")  # Werkzeugergebnis wird verworfen, nicht ins Eingabefeld gelegt
        self.assertEqual(session.discard_last_user(), "Frage 2")

    def test_fit_never_starts_with_orphaned_tool_result(self):
        history = [Message("user", "a" * 600), Message("assistant", "b" * 600 + " [[mcp:zeit]]"),
                   Message("user", "c" * 600, TOOL_RESULT, "zeit"), Message("assistant", "d" * 600),
                   Message("user", "e" * 600), Message("assistant", "f" * 600)]
        kept, dropped = ContextManager().fit("S", history, budget_tokens=1100)
        self.assertGreater(dropped, 0)
        self.assertEqual(kept[0].role, "user")
        self.assertFalse(kept[0].is_tool)

    def test_load_returns_unanswered_question_and_drops_orphan_result(self):
        session = self.make()
        pending = session.load([Message("user", "Hi"), Message("assistant", "Hallo"), Message("user", "Und?")])
        self.assertEqual((pending, len(session.history)), ("Und?", 2))
        pending = session.load([Message("user", "Hi"), Message("assistant", "[[mcp:zeit]]"),
                                Message("user", "Montag", TOOL_RESULT, "zeit")])
        self.assertEqual((pending, [m.role for m in session.history]), ("", ["user", "assistant"]))


class ChatLogTest(unittest.TestCase):
    messages = [Message("user", "Wie spät ist es?"), Message("assistant", "Ich schaue nach. [[mcp:zeit]]"),
                Message("user", "Montag, 12:00", TOOL_RESULT, "zeit"), Message("assistant", "Es ist 12 Uhr. 😊"),
                Message("user", "Fehler?"), Message("assistant", "[[mcp:x]]"),
                Message("user", "Unbekannt", TOOL_ERROR, "x"), Message("assistant", "Klappt nicht.")]

    def test_json_roundtrip_keeps_tool_messages(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "c.json"
            chatlog.save_json(path, self.messages, "mia", "Alex")
            loaded = chatlog.load_chat(path, {"Alex"}, {"Mia"})
        self.assertEqual(loaded.messages, self.messages)
        self.assertEqual(loaded.persona, "mia")

    def test_text_roundtrip_and_old_export_format(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "c.txt"
            chatlog.save_text(path, self.messages[:4], "Alex", "Mia")
            loaded = chatlog.load_chat(path, {"Alex"}, {"Mia"})
            self.assertEqual([(m.role, m.kind, m.content) for m in loaded.messages],
                             [(m.role, m.kind, m.content) for m in self.messages[:4]])
            path.write_text("Nutzer: Hi Mia, mir geht es gut.\nWas denkst du?\n\nMia: Schön!\n\n\nMia: Echt.\n"
                            "Nutzer: Ja", encoding="utf-8")
            old = chatlog.load_chat(path, {"Alex"}, {"Mia"}).messages
        self.assertEqual([(m.role, m.content) for m in old], [
            ("user", "Hi Mia, mir geht es gut.\nWas denkst du?"), ("assistant", "Schön!\n\nEcht."), ("user", "Ja")])

    def test_invalid_files_raise_chat_file_error(self):
        with tempfile.TemporaryDirectory() as tmp:
            for name, text in (("a.json", "{"), ("b.json", '{"messages": 5}'),
                               ("c.json", '{"messages": [{"role": "robot", "content": "x"}]}'),
                               ("d.txt", "kein Chat hier")):
                path = Path(tmp) / name
                path.write_text(text, encoding="utf-8")
                with self.assertRaises(chatlog.ChatFileError, msg=name):
                    chatlog.load_chat(path, {"Alex"}, {"Mia"})
            with self.assertRaises(chatlog.ChatFileError):
                chatlog.load_chat(Path(tmp) / "fehlt.json", {"Alex"}, {"Mia"})


class PersonaFilesTest(unittest.TestCase):
    def test_save_load_delete_roundtrip_with_tools_and_ids(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)
            first = Persona(name="Tom Müller", rules=["Sei knapp."], tools=["zeit"], avatar="avatars/x.png")
            self.assertEqual(save_persona(first, directory), "tom_müller")
            self.assertEqual(unique_id("Tom Müller", directory), "tom_müller_2")
            loaded = load_persona("tom_müller", directory)
            self.assertEqual((loaded.name, loaded.rules, loaded.tools, loaded.id),
                             ("Tom Müller", ["Sei knapp."], ["zeit"], "tom_müller"))
            self.assertNotIn('"id"', (directory / "tom_müller.json").read_text(encoding="utf-8"))
            self.assertEqual(list_personas(directory), ["tom_müller"])
            delete_persona("tom_müller", directory)
            self.assertEqual(list_personas(directory), [])
            self.assertEqual(load_persona("../config", directory).name, "Mia")  # kein Pfad-Ausbruch
        self.assertEqual(slugify("  ../Ä b!  "), "ä_b")

    def test_prompt_has_anti_repetition_rules_and_tool_section(self):
        prompt = Persona(name="Mia").system_prompt("Alex", "Werkzeuge:\n- zeit")
        for needle in ("Wiederhole dich nicht", "kein HTML", "nicht in jeder Antwort", "Werkzeuge:\n- zeit"):
            self.assertIn(needle, prompt)

    def test_sampling_params_are_sent_only_when_not_neutral(self):
        self.assertEqual(Config().sampling_params(), {"top_p": 0.9, "frequency_penalty": 0.3,
                                                      "presence_penalty": 0.2, "repeat_penalty": 1.1})
        online = Config(repeat_penalty=0, top_p=1.0, frequency_penalty=0, presence_penalty=0)
        self.assertEqual(online.sampling_params(), {})


# --- GUI Ende-zu-Ende mit Fake-LLM-Server und echtem MCP-Demo-Server -----------------------------

REQUESTS: list[dict] = []


def sse(*texts) -> bytes:
    chunks = [{"choices": [{"delta": {"content": t}}]} for t in texts]
    chunks.append({"choices": [], "usage": {"prompt_tokens": 100, "completion_tokens": 5}})
    return ("".join(f"data: {json.dumps(c, ensure_ascii=False)}\n\n" for c in chunks) + "data: [DONE]\n\n").encode()


class _Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        REQUESTS.append(body)
        last = body["messages"][-1]["content"]
        if body["model"] == "loop":  # ruft endlos Werkzeuge auf
            data = sse("Noch eins. [[mcp:rechnen|1+1]]")
        elif last.startswith(("[Ergebnis von", "[Fehler bei")):
            data = sse("Das Ergebnis ", "ist 42. ", "Das Ergebnis ist 42.")  # Satz doppelt -> wird bereinigt
        else:
            data = sse("Ich rechne kurz. ", "[[mcp:rech", "nen|6*7]]", " Ergebnis: 99 erfunden")
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)


def make_png(path: Path, width=100, height=80) -> None:
    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)

    raw = b"".join(b"\x00" + b"\xff\x40\x40" * width for _ in range(height))
    path.write_bytes(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
                     + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b""))


class GuiToolsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), _Handler)
        threading.Thread(target=cls.server.serve_forever, daemon=True).start()
        cls.url = f"http://127.0.0.1:{cls.server.server_port}/v1"

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()

    def setUp(self):
        import tkinter as tk
        from chat_app.gui import ChatApp

        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        root = Path(self.tmp.name)
        self.personas = root / "personas"
        save_persona(Persona(name="Mia", greeting="Hallo!", tools=["rechnen"]), self.personas)
        self.registry = demo_registry()
        self.addCleanup(self.registry.shutdown)
        self.tk = tk.Tk()
        self.tk.withdraw()
        self.addCleanup(self.tk.destroy)
        cfg = Config(base_url=self.url, model="m", user_name="Alex", persona="mia", confirm_tools=False)
        self.app = ChatApp(self.tk, config=cfg, config_path=root / "config.json", personas_dir=self.personas,
                           chats_dir=root / "chats", registry=self.registry)

    def wait(self):
        deadline = time.time() + 15
        while self.app.streaming and time.time() < deadline:
            self.tk.update()
            time.sleep(0.01)
        self.tk.update()
        self.assertFalse(self.app.streaming)

    def ask(self, text):
        self.app.input.insert("1.0", text)
        self.app.send()
        self.wait()

    def test_tool_roundtrip_save_and_load_without_reexecution(self):
        REQUESTS.clear()
        self.ask("Was ist 6 mal 7?")
        history = self.app.session.history
        self.assertEqual([(m.role, m.kind) for m in history],
                         [("user", "chat"), ("assistant", "chat"), ("user", TOOL_RESULT), ("assistant", "chat")])
        self.assertEqual(history[1].content, "Ich rechne kurz. [[mcp:rechnen|6*7]]")  # erfundenes Ergebnis verworfen
        self.assertEqual((history[2].tool, history[2].content), ("rechnen", "42"))
        self.assertEqual(history[3].content, "Das Ergebnis ist 42.")  # doppelter Satz bereinigt
        self.assertEqual(len(REQUESTS), 2)
        system = REQUESTS[0]["messages"][0]["content"]
        self.assertIn("[[mcp:rechnen|12*(3+4)]]", system)
        self.assertEqual(REQUESTS[0]["frequency_penalty"], 0.3)  # Wiederholungs-Parameter gesendet
        self.assertEqual(REQUESTS[0]["repeat_penalty"], 1.1)
        second = REQUESTS[1]["messages"]
        self.assertEqual([m["role"] for m in second], ["system", "user", "assistant", "user"])
        self.assertIn("[Ergebnis von rechnen]\n42", second[-1]["content"])
        shown = self.app.chat.get("1.0", "end")
        self.assertIn("» Werkzeug rechnen: 6*7", shown)
        self.assertIn("« Ergebnis von rechnen: 42", shown)
        self.assertNotIn("[[mcp", shown)
        self.assertNotIn("99", shown)

        path = Path(self.tmp.name) / "chat.json"
        self.assertTrue(self.app.save_chat_to(path))
        self.app.session.reset()
        with mock.patch.object(self.registry, "run", side_effect=AssertionError("Werkzeug beim Laden ausgeführt")) as run:
            REQUESTS.clear()
            self.assertTrue(self.app.load_chat_from(path, confirm=False))
            self.tk.update()
            time.sleep(0.2)
            self.tk.update()
            self.assertEqual((run.call_count, len(REQUESTS)), (0, 0))
        self.assertEqual(self.app.session.history, history)
        shown = self.app.chat.get("1.0", "end")
        self.assertIn("» Werkzeug rechnen: 6*7", shown)
        self.assertIn("Chat geladen: chat.json", shown)

        self.ask("Und 7 mal 7?")  # nach dem Laden normal weiterchatten
        self.assertEqual(len(self.app.session.history), 8)

    def test_decline_feeds_refusal_back_and_call_limit_stops_loops(self):
        self.app.config.confirm_tools = True
        with mock.patch("chat_app.gui.messagebox.askyesno", return_value=False) as ask:
            self.ask("Rechne bitte")
        self.assertEqual(ask.call_count, 1)
        self.assertEqual(self.app.session.history[2].kind, TOOL_ERROR)
        self.assertIn("abgelehnt", self.app.session.history[2].content)

        self.app.session.reset()
        self.app.config.confirm_tools, self.app.config.model = False, "loop"
        self.ask("Mach weiter")
        kinds = [m.kind for m in self.app.session.history]
        self.assertEqual(kinds.count(TOOL_RESULT), self.app.config.max_tool_calls)
        self.assertIn("Limit für Werkzeugaufrufe", self.app.chat.get("1.0", "end"))

    def test_loaded_text_chat_and_trailing_question_goes_to_input(self):
        path = Path(self.tmp.name) / "alt.txt"
        path.write_text("Alex: Hi Mia\n\nMia: Hallo!\n\nAlex: Noch da?", encoding="utf-8")
        self.assertTrue(self.app.load_chat_from(path, confirm=False))
        self.assertEqual(len(self.app.session.history), 2)
        self.assertEqual(self.app.input.get("1.0", "end").strip(), "Noch da?")
        bad = Path(self.tmp.name) / "bad.json"
        bad.write_text("{", encoding="utf-8")
        with mock.patch("chat_app.gui.messagebox.showerror") as error:
            self.assertFalse(self.app.load_chat_from(bad, confirm=False))
        self.assertEqual(error.call_count, 1)
        self.assertEqual(len(self.app.session.history), 2)  # fehlgeschlagenes Laden lässt den Chat unverändert

    def test_companion_editor_creates_avatar_and_switches(self):
        from chat_app.persona_editor import PersonaEditor

        png = Path(self.tmp.name) / "tom.png"
        make_png(png)
        editor = PersonaEditor(self.app, select="mia")
        self.addCleanup(editor.destroy)
        self.assertIn("rechnen", editor.tool_vars)
        self.assertTrue(editor.tool_vars["rechnen"].get())
        editor.new()
        editor.name.insert(0, "Tom")
        editor.greeting.delete("1.0", "end")
        editor.greeting.insert("1.0", "Moin!")
        editor.rules.insert("1.0", "Sei knapp.\n\nSei nett.")
        editor.tool_vars["zeit"].set(True)
        self.assertTrue(editor.set_avatar(png))
        self.assertEqual(editor.save(), "tom")
        saved = load_persona("tom", self.personas)
        self.assertEqual((saved.rules, saved.tools, saved.greeting), (["Sei knapp.", "Sei nett."], ["zeit"], "Moin!"))
        self.assertEqual(saved.avatar, "avatars/tom.png")
        self.assertIsNotNone(saved.avatar_path(self.personas))
        self.assertIn("tom", self.app.persona_combo.cget("values"))

        self.app.persona_combo.set("tom")
        self.app._persona_changed()
        self.assertEqual(self.app.session.persona.name, "Tom")
        self.assertIsNotNone(self.app.avatar._photo)  # Avatar in der Ecke angezeigt
        self.assertEqual(self.app.config.persona, "tom")
        self.assertIn("Tom: Moin!", self.app.chat.get("1.0", "end"))

        editor.clear_avatar()
        self.assertEqual(editor.save(), "tom")
        self.assertFalse((self.personas / "avatars" / "tom.png").exists())  # Bilddatei aufgeräumt
        self.assertIsNone(self.app.avatar._photo)  # Platzhalter

        with mock.patch("chat_app.persona_editor.messagebox.askyesno", return_value=True):
            editor.delete()
        self.assertEqual(list_personas(self.personas), ["mia"])
        self.assertEqual(self.app.session.persona.id, "mia")  # aktiver Companion gelöscht -> Wechsel
        self.assertEqual(str(editor.delete_btn.cget("state")), "disabled")  # letzter Companion nicht löschbar


if __name__ == "__main__":
    unittest.main()
