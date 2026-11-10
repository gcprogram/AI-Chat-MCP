import contextlib
import importlib.util
import io
import json
import sys
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from unittest import mock

SPEC = importlib.util.spec_from_file_location("model_eval", Path(__file__).resolve().parent.parent / "tools" / "model_eval.py")
model_eval = importlib.util.module_from_spec(SPEC)
sys.modules["model_eval"] = model_eval  # dataclasses schlägt das eigene Modul nach
SPEC.loader.exec_module(model_eval)


def _sse(text: str) -> bytes:
    chunks = [{"choices": [{"delta": {"content": text}}]}]
    return ("".join(f"data: {json.dumps(c)}\n\n" for c in chunks) + "data: [DONE]\n\n").encode()


class _Handler(BaseHTTPRequestHandler):
    """Antwortet wie ein brauchbares Modell auf Todo-Wünsche und sonst frei - ohne echtes Modell."""

    def log_message(self, *args):
        pass

    def do_GET(self):
        data = json.dumps({"data": [{"id": "fake-model"}]}).encode()
        self.send_response(200)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        user = body["messages"][-1]["content"]
        reply = "[[mcp:todo-add|Zahnarzt|tomorrow]]" if user.startswith("Erinnere") else "Hallo, gern."
        data = _sse(reply)
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)


class ModelEvalTest(unittest.TestCase):
    def test_check_judges_markers_and_arguments(self):
        add = model_eval.Case("x", "todo-add", (r"zahnarzt", r"^(tomorrow|morgen)$"))
        self.assertTrue(model_eval.check(add, "[[mcp:todo-add|Zahnarzt anrufen|tomorrow]]")[0])
        self.assertEqual("kein Marker", model_eval.check(add, "Mach ich!")[1])
        self.assertIn("falsches Werkzeug", model_eval.check(add, "[[mcp:todo-list]]")[1])
        self.assertIn("Argument 2 falsch", model_eval.check(add, "[[mcp:todo-add|Zahnarzt|next week]]")[1])
        self.assertIn("Argument 2 falsch", model_eval.check(add, "[[mcp:todo-add|Zahnarzt]]")[1])  # fehlendes Datum
        chat = model_eval.Case("Hallo")
        self.assertTrue(model_eval.check(chat, "Hallo, mir geht es gut.")[0])
        self.assertFalse(model_eval.check(chat, "[[mcp:todo-list]]")[0])  # Marker in einem Chatfall ist ein Fehler

    def test_prompt_lists_the_secretary_tools(self):
        registry = model_eval.build_registry()
        self.assertEqual(["todo-add", "todo-done", "todo-list", "weather"], registry.available())
        self.assertIn("[[mcp:todo-add|", registry.prompt_section(registry.available()))

    def test_every_tool_case_names_a_known_tool(self):
        known = set(model_eval.build_registry().available())
        self.assertTrue(all(c.tool in known for c in model_eval.CASES if c.tool))
        self.assertGreaterEqual(len([c for c in model_eval.CASES if not c.tool]), 5)  # auch Chatfälle: kein Marker ohne Grund

    def test_run_against_a_fake_server(self):
        server = ThreadingHTTPServer(("127.0.0.1", 0), _Handler)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        try:
            out = io.StringIO()
            argv = ["model_eval.py", "--url", f"http://127.0.0.1:{server.server_port}/v1", "--api-key", "x"]
            with mock.patch("sys.argv", argv), contextlib.redirect_stdout(out):
                self.assertEqual(0, model_eval.main())
        finally:
            server.shutdown()
        text = out.getvalue()
        chat_cases = len([c for c in model_eval.CASES if not c.tool])
        self.assertIn(f"fake-model: {chat_cases + 1}/{len(model_eval.CASES)} richtig", text)  # Chatfälle + der eine Todo-Fall
        self.assertIn("kein Marker", text)


if __name__ == "__main__":
    unittest.main()
