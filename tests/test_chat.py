import json
import tempfile
import threading
import time
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from chat_app.config import Config
from chat_app.context import ContextManager, Message
from chat_app.llm_client import ChatClient, LLMError
from chat_app.persona import Persona, list_personas, load_persona
from chat_app.session import ChatSession

REQUESTS: list[dict] = []


def sse(*chunks) -> bytes:
    body = "".join(f"data: {json.dumps(c, ensure_ascii=False)}\n\n" for c in chunks)
    return (body + "data: [DONE]\n\n").encode("utf-8")


class _Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_GET(self):
        data = json.dumps({"data": [{"id": "chat-model"}, {"id": "text-embedding-x"}]}).encode()
        self.send_response(200)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        REQUESTS.append(body)
        if body["model"] == "bad":
            data = b"kaputt"
            self.send_response(500)
        else:
            data = sse(
                {"choices": [{"delta": {"reasoning_content": "Hmm, "}}]},
                {"choices": [{"delta": {"content": "Hallo "}}]},
                {"choices": [{"delta": {"content": "Grüße, du! 😊"}}]},
                {"choices": [], "usage": {"prompt_tokens": 120, "completion_tokens": 5}},
            )
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream")  # bewusst ohne charset
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)


class ServerCase(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), _Handler)
        threading.Thread(target=cls.server.serve_forever, daemon=True).start()
        cls.url = f"http://127.0.0.1:{cls.server.server_port}/v1"

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()


class ClientTest(ServerCase):
    def test_stream_parses_reasoning_content_usage_and_utf8(self):
        events = list(ChatClient(self.url).stream_chat(
            "m", [{"role": "user", "content": "hi"}], temperature=0.5, max_tokens=50, stop=["\nNutzer:"]))
        self.assertEqual([e.kind for e in events], ["reasoning", "content", "content", "usage"])
        self.assertEqual("".join(e.text for e in events if e.kind == "content"), "Hallo Grüße, du! 😊")
        self.assertEqual(events[-1].usage["prompt_tokens"], 120)
        sent = REQUESTS[-1]
        self.assertEqual((sent["stream"], sent["max_tokens"], sent["stop"]), (True, 50, ["\nNutzer:"]))

    def test_http_error_raises(self):
        with self.assertRaises(LLMError):
            list(ChatClient(self.url).stream_chat("bad", [], temperature=0, max_tokens=5))
        with self.assertRaises(LLMError):
            list(ChatClient("http://127.0.0.1:1/v1").stream_chat("m", [], temperature=0, max_tokens=5))

    def test_cancel_stops_stream(self):
        cancel = threading.Event()
        cancel.set()
        self.assertEqual(list(ChatClient(self.url).stream_chat("m", [], temperature=0, max_tokens=5, cancel=cancel)), [])

    def test_list_models_skips_embeddings(self):
        self.assertEqual(ChatClient(self.url).list_models(), ["chat-model"])


class ContextTest(unittest.TestCase):
    def test_drops_oldest_in_whole_turns_and_starts_with_user(self):
        history = []
        for i in range(20):
            history += [Message("user", f"Frage {i} " + "x" * 300), Message("assistant", f"Antwort {i} " + "y" * 300)]
        kept, dropped = ContextManager().fit("System", history, budget_tokens=1000)
        self.assertGreater(dropped, 0)
        self.assertEqual(kept[0].role, "user")
        self.assertEqual(kept[-1], history[-1])
        self.assertEqual(len(kept) + dropped, len(history))

    def test_last_message_always_kept_and_small_history_untouched(self):
        big = [Message("user", "z" * 100000)]
        self.assertEqual(ContextManager().fit("S", big, 100), (big, 0))

    def test_calibration_moves_estimate_towards_server_value(self):
        ctx = ContextManager()
        ctx.calibrate(chars=5000, prompt_tokens=1000)  # 5 Zeichen/Token
        self.assertGreater(ctx.chars_per_token, 3.0)
        ctx.calibrate(chars=0, prompt_tokens=10)  # ungültig -> ignoriert
        self.assertLess(ctx.chars_per_token, 6.0)


class PersonaAndSessionTest(unittest.TestCase):
    def test_system_prompt_fixes_roles(self):
        prompt = Persona(name="Mia", greeting="Hi!").system_prompt("Alex")
        for needle in ("Du bist Mia", "Alex", "du", "niemals Zeilen für Alex", "1 bis 4 Sätze", "Hi!"):
            self.assertIn(needle, prompt)
        self.assertIn("\nAlex:", Persona(name="Mia").stop_sequences("Alex"))

    def test_shipped_persona_loads_and_bad_file_falls_back(self):
        self.assertIn("companion", list_personas())
        self.assertEqual(load_persona("companion").name, "Mia")
        self.assertEqual(load_persona("gibt-es-nicht").name, "Mia")

    def test_request_is_stateless_and_history_not_duplicated(self):
        session = ChatSession(Config(), Persona(name="Mia"))
        session.add_user("Hallo")
        session.add_assistant("Hi")
        session.add_user("Wie geht's?")
        messages = session.request_messages()
        self.assertEqual([m["role"] for m in messages], ["system", "user", "assistant", "user"])
        self.assertEqual(session.discard_last_user(), "Wie geht's?")
        self.assertEqual(len(session.history), 2)

    def test_long_chat_stays_within_context_window(self):
        cfg = Config(context_tokens=2048, max_reply_tokens=300).sanitize()
        session = ChatSession(cfg, Persona())
        for i in range(200):
            session.add_user(f"Frage {i} " + "wort " * 40)
            session.add_assistant(f"Antwort {i} " + "wort " * 60)
            session.add_user("Weiter")
            self.assertLessEqual(session.estimated_tokens(), cfg.context_tokens - cfg.max_reply_tokens)
            session.discard_last_user()
        self.assertGreater(session.dropped, 0)


class ConfigTest(unittest.TestCase):
    def test_legacy_config_and_corrupt_file(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "c.json"
            path.write_text(json.dumps({"ip": "10.0.0.5", "port": "4321", "model": "m"}), encoding="utf-8")
            self.assertEqual(Config.load(path).base_url, "http://10.0.0.5:4321/v1")
            path.write_text("{", encoding="utf-8")
            self.assertEqual(Config.load(path).base_url, "http://127.0.0.1:1234/v1")

    def test_sanitize_clamps(self):
        cfg = Config(temperature="x", max_reply_tokens=10**9, context_tokens=5).sanitize()
        self.assertEqual((cfg.temperature, cfg.max_reply_tokens, cfg.context_tokens), (0.7, 8192, 1024))


class GuiEndToEndTest(ServerCase):
    def test_send_streams_reply_and_keeps_only_content_in_history(self):
        import tkinter as tk
        from chat_app.gui import ChatApp

        root = tk.Tk()
        root.withdraw()
        try:
            app = ChatApp(root)
            app.config.base_url, app.config.model, app.config.user_name = self.url, "m", "Alex"
            app.input.insert("1.0", "Hallo Mia")
            app.send()
            deadline = time.time() + 10
            while app.streaming and time.time() < deadline:
                root.update()
                time.sleep(0.01)
            root.update()
            self.assertFalse(app.streaming)
            self.assertEqual([(m.role, m.content) for m in app.session.history],
                             [("user", "Hallo Mia"), ("assistant", "Hallo Grüße, du! 😊")])
            shown = app.chat.get("1.0", tk.END)
            self.assertIn("Alex: Hallo Mia", shown)
            self.assertIn("Hmm, ", shown)  # Reasoning wird angezeigt ...
            self.assertNotIn("Hmm", app.session.history[1].content)  # ... aber nie gespeichert
            self.assertEqual(REQUESTS[-1]["messages"][0]["role"], "system")
            self.assertNotIn("Du bist Mia", shown)  # System-Prompt nie im Transkript
            self.assertEqual(app.session.context.chars_per_token != 3.0, True)  # mit Server-Usage kalibriert

            app.config.model = "bad"  # Fehlerpfad: Frage zurück ins Eingabefeld, Verlauf unverändert
            app.input.insert("1.0", "Noch eine Frage")
            app.send()
            while app.streaming and time.time() < deadline:
                root.update()
                time.sleep(0.01)
            root.update()
            self.assertEqual(len(app.session.history), 2)
            self.assertEqual(app.input.get("1.0", tk.END).strip(), "Noch eine Frage")
            self.assertIn("[Fehler]", app.chat.get("1.0", tk.END))
        finally:
            root.destroy()


if __name__ == "__main__":
    unittest.main()
