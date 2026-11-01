"""Natives Tool-Calling: tools-Parameter, zusammengesetzte tool_calls-Deltas, Verlauf im Chat-API-Format."""
import gc
import json
import tempfile
import threading
import time
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from unittest import mock

from chat_app.config import Config
from chat_app.context import CHAT, TOOL_ERROR, TOOL_RESULT, TOOL_SHOWN, Message
from chat_app.llm_client import ChatClient
from chat_app.persona import Persona, load_persona, save_persona
from chat_app.session import ChatSession
from test_aliases import alias_registry


class NativeUnitTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.registry = alias_registry()

    @classmethod
    def tearDownClass(cls):
        cls.registry.shutdown()

    def test_native_tools_schema_is_small(self):
        tools = {t["function"]["name"]: t["function"] for t in self.registry.native_tools(["sieben", "rechne", "rechne-null", "nix"])}
        self.assertEqual(list(tools), ["sieben", "rechne", "rechne-null"])
        self.assertEqual(tools["sieben"]["parameters"], {"type": "object", "properties": {}})  # feste Argumente unsichtbar
        self.assertEqual(tools["rechne"]["parameters"]["required"], ["expression"])
        self.assertEqual(tools["rechne"]["parameters"]["properties"]["expression"]["type"], "string")
        self.assertNotIn("required", tools["rechne-null"]["parameters"])  # Standardwert -> optional
        self.assertEqual(tools["sieben"]["description"], "Zeigt das Ergebnis von 6 mal 7.")

    def test_native_call_to_marker_and_arguments(self):
        marker, arguments = self.registry.native_call("rechne", '{"expression": "6*7"}')
        self.assertEqual((marker, arguments), ("[[mcp:rechne|6*7]]", {"expression": "6*7"}))
        marker, arguments = self.registry.native_call("Rechne_Null", "")
        self.assertEqual((marker, arguments), ("[[mcp:rechne-null]]", {}))
        marker, _ = self.registry.native_call("rechne", '{"expression": "a|b]\\nc"}')
        self.assertEqual(marker, "[[mcp:rechne|a b c]]")  # Marker bleibt lesbar und wohlgeformt
        marker, _ = self.registry.native_call("rechne", "kein json")
        self.assertEqual(marker, "[[mcp:rechne]]")

    def test_run_uses_real_arguments_not_marker_text(self):
        from chat_app.tools import ToolCall

        call = ToolCall("rechne", "a b c", "[[mcp:rechne|a b c]]", 0, 0, {"expression": "6*7"})
        self.assertEqual(self.registry.run(call, ["rechne"]).text, "42")
        self.assertEqual(self.registry.run(ToolCall("sieben", "", "", 0, 0, {"expression": "99"}), ["sieben"]).text, "42")

    def test_tool_call_fragments_are_assembled(self):
        calls = {}
        chunks = [
            {"choices": [{"delta": {"tool_calls": [{"index": 0, "id": "x", "function": {"name": "rech", "arguments": ""}}]}}]},
            {"choices": [{"delta": {"tool_calls": [{"index": 0, "function": {"name": "ne", "arguments": '{"expr'}}]}}]},
            {"choices": [{"delta": {"tool_calls": [{"index": 0, "function": {"arguments": 'ession": "6*7"}'}}]}}]}]
        for chunk in chunks:
            list(ChatClient._events(json.dumps(chunk), calls))
        self.assertEqual(calls, {0: {"name": "rechne", "arguments": '{"expression": "6*7"}'}})
        repeated = {}
        for _ in range(2):  # Server, die den Namen in jedem Fragment wiederholen
            list(ChatClient._events(json.dumps({"choices": [{"delta": {"tool_calls": [
                {"index": 0, "function": {"name": "rechne", "arguments": "{"}}]}}]}), repeated))
        self.assertEqual(repeated[0], {"name": "rechne", "arguments": "{{"})


class NativeSessionTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.registry = alias_registry()

    @classmethod
    def tearDownClass(cls):
        cls.registry.shutdown()

    def session(self, mode="native"):
        return ChatSession(Config(user_name="Alex"), Persona(name="Mia", tools=["rechne", "sieben"], tool_mode=mode),
                           self.registry)

    def test_history_becomes_tool_calls_and_tool_messages(self):
        s = self.session()
        s.add_user("Was ist 6 mal 7?")
        s.add_assistant("Ich rechne. [[mcp:rechne|6*7]]")
        s.add_tool_result("rechne", "42", True)
        s.add_assistant("Das ist 42.")
        s.add_user("Und 1/0?")
        s.add_assistant("[[mcp:rechne|1/0]]")
        s.add_tool_result("rechne", "Rechenfehler", False)
        messages = s.request_messages()
        self.assertEqual([m["role"] for m in messages],
                         ["system", "user", "assistant", "tool", "assistant", "user", "assistant", "tool"])
        call = messages[2]
        self.assertEqual(call["content"], "Ich rechne.")
        self.assertEqual(call["tool_calls"][0]["function"], {"name": "rechne", "arguments": '{"expression": "6*7"}'})
        self.assertEqual((messages[3]["tool_call_id"], messages[3]["content"]), (call["tool_calls"][0]["id"], "42"))
        self.assertEqual(messages[6]["tool_calls"][0]["id"], messages[7]["tool_call_id"])
        self.assertNotEqual(messages[6]["tool_calls"][0]["id"], call["tool_calls"][0]["id"])
        self.assertEqual(messages[7]["content"], "Fehler: Rechenfehler")
        self.assertNotIn("[[mcp", messages[0]["content"])  # Werkzeuge beschreibt das Chat-Template, nicht der Prompt
        self.assertIn("Erfinde nie ein Ergebnis", messages[0]["content"])
        self.assertEqual(len(s.native_tools), 2)

    def test_shown_result_is_a_tool_message_and_unanswered_call_becomes_text(self):
        s = self.session()
        s.add_user("Zeig es")
        s.add_assistant("[[mcp:sieben]]")
        s.add_tool_shown("sieben", "42")
        s.add_user("Danke")
        messages = s.request_messages()
        self.assertEqual([m["role"] for m in messages], ["system", "user", "assistant", "tool", "user"])
        self.assertEqual(messages[2]["tool_calls"][0]["function"]["arguments"], "{}")
        self.assertEqual(messages[4]["content"], "Danke")  # keine Verpackung wie im Marker-Modus

        s2 = self.session()
        s2.add_user("Abgebrochen?")
        s2.add_assistant("Moment. [[mcp:sieben]]")  # Ergebnis fehlt (Abbruch)
        s2.add_user("Hallo?")
        messages = s2.request_messages()
        self.assertEqual([m["role"] for m in messages], ["system", "user", "assistant", "user"])
        self.assertEqual(messages[2], {"role": "assistant", "content": "Moment."})

    def test_marker_mode_unchanged_and_no_tools_without_enabled_tools(self):
        marker = self.session("marker")
        self.assertEqual(marker.native_tools, [])
        self.assertIn("[[mcp:NAME|ARGUMENT]]", marker.system_prompt)
        empty = ChatSession(Config(), Persona(tool_mode="native"), self.registry)
        self.assertFalse(empty.native)  # nativ ohne freigeschaltete Werkzeuge: normaler Chat
        self.assertEqual(empty.native_tools, [])

    def test_estimate_counts_tool_descriptions(self):
        native, marker = self.session(), self.session("marker")
        for s in (native, marker):
            s.add_user("Hallo")
        self.assertGreater(native.estimated_tokens(), 0)
        plain = ChatSession(Config(user_name="Alex"), Persona(name="Mia", tool_mode="native"), self.registry)
        plain.add_user("Hallo")
        self.assertGreater(native.estimated_tokens(), plain.estimated_tokens())

    def test_persona_file_keeps_tool_mode_and_falls_back(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)
            save_persona(Persona(name="Nativ", tool_mode="native"), directory)
            self.assertEqual(load_persona("nativ", directory).tool_mode, "native")
            (directory / "alt.json").write_text(json.dumps({"name": "Alt"}), encoding="utf-8")
            (directory / "falsch.json").write_text(json.dumps({"name": "X", "tool_mode": "magisch"}), encoding="utf-8")
            self.assertEqual(load_persona("alt", directory).tool_mode, "marker")
            self.assertEqual(load_persona("falsch", directory).tool_mode, "marker")


REQUESTS = []


def sse_chunks(chunks):
    chunks = [*chunks, {"choices": [], "usage": {"prompt_tokens": 50}}]
    return ("".join(f"data: {json.dumps(c, ensure_ascii=False)}\n\n" for c in chunks) + "data: [DONE]\n\n").encode()


def delta(**kw):
    return {"choices": [{"delta": kw}]}


class _Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        REQUESTS.append(body)
        last = body["messages"][-1]
        if last["role"] == "tool":
            chunks = [delta(content="Das Ergebnis lautet " + last["content"] + ".")]
        elif "tools" in body and body["model"] in ("rechne", "sieben"):  # Aufruf in Fragmenten, wie LM Studio ihn streamt
            name, arguments = ("rechne", ['{"expres', 'sion": "6*7"}']) if body["model"] == "rechne" else ("sieben", ["{}"])
            chunks = [delta(content="Ich schaue nach."),
                      delta(tool_calls=[{"index": 0, "id": "call_x", "type": "function",
                                         "function": {"name": name, "arguments": ""}}])]
            chunks += [delta(tool_calls=[{"index": 0, "function": {"arguments": part}}]) for part in arguments]
        else:
            chunks = [delta(content="Gern geschehen.")]
        data = sse_chunks(chunks)
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)


class GuiNativeTest(unittest.TestCase):
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

        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        root = Path(tmp.name)
        save_persona(Persona(name="Mia", greeting="Hallo!", tools=["rechne", "sieben"], tool_mode="native"),
                     root / "personas")
        self.registry = alias_registry()
        self.addCleanup(self.registry.shutdown)
        self.tk = tk.Tk()
        self.tk.withdraw()
        self.addCleanup(self.tk.destroy)
        cfg = Config(base_url=self.url, model="rechne", user_name="Alex", persona="mia", confirm_tools=False)
        self.app = ChatApp(self.tk, config=cfg, config_path=root / "config.json", personas_dir=root / "personas",
                           chats_dir=root / "chats", registry=self.registry)
        REQUESTS.clear()
        # Tk-Variablen (z. B. vom Editor) müssen im Hauptthread freigegeben werden, nicht von einem Streaming-Thread
        self.addCleanup(gc.collect)

    def ask(self, text, model):
        self.app.config.model = model
        self.app.input.insert("1.0", text)
        self.app.send()
        deadline = time.time() + 15
        while self.app.streaming and time.time() < deadline:
            self.tk.update()
            time.sleep(0.01)
        self.tk.update()
        self.assertFalse(self.app.streaming)

    def test_native_roundtrip_with_model_answer(self):
        self.ask("Was ist 6 mal 7?", "rechne")
        self.assertEqual(len(REQUESTS), 2)
        first = REQUESTS[0]
        self.assertEqual([t["function"]["name"] for t in first["tools"]], ["rechne", "sieben"])
        self.assertNotIn("[[mcp", first["messages"][0]["content"])
        history = self.app.session.history
        self.assertEqual([(m.role, m.kind) for m in history],
                         [("user", CHAT), ("assistant", CHAT), ("user", TOOL_RESULT), ("assistant", CHAT)])
        self.assertEqual(history[1].content, "Ich schaue nach. [[mcp:rechne|6*7]]")  # lesbare Marker-Form im Verlauf
        self.assertEqual(history[2].content, "42")  # mit den echten Argumenten ausgeführt
        self.assertEqual(history[3].content, "Das Ergebnis lautet 42.")
        second = REQUESTS[1]["messages"]
        self.assertEqual([m["role"] for m in second], ["system", "user", "assistant", "tool"])
        call = second[2]["tool_calls"][0]
        self.assertEqual((call["function"]["name"], json.loads(call["function"]["arguments"])),
                         ("rechne", {"expression": "6*7"}))
        self.assertEqual((second[3]["tool_call_id"], second[3]["content"]), (call["id"], "42"))
        shown = self.app.chat.get("1.0", "end")
        self.assertIn("» Werkzeug rechne: 6*7", shown)
        self.assertNotIn("[[mcp", shown)

    def test_native_show_mode_and_marker_companion_sends_no_tools(self):
        self.ask("Zeig 6 mal 7", "sieben")
        self.assertEqual(len(REQUESTS), 1)  # show: keine zweite Modellrunde
        self.assertEqual([m.kind for m in self.app.session.history], [CHAT, CHAT, TOOL_SHOWN])
        self.assertIn("\n42\n", self.app.chat.get("1.0", "end"))
        self.ask("Danke", "plain")
        self.assertEqual([m["role"] for m in REQUESTS[1]["messages"]], ["system", "user", "assistant", "tool", "user"])
        self.assertEqual(REQUESTS[1]["messages"][3]["content"], "42")

        self.app.session.reset()
        self.app.session.persona.tool_mode = "marker"
        REQUESTS.clear()
        self.ask("Hallo", "plain")
        self.assertNotIn("tools", REQUESTS[0])

    def test_editor_saves_tool_mode(self):
        from chat_app.persona_editor import MODE_LABELS, PersonaEditor

        editor = PersonaEditor(self.app, select="mia")
        self.addCleanup(editor.destroy)
        self.assertEqual(editor.mode.get(), MODE_LABELS["native"])
        editor.mode.set(MODE_LABELS["marker"])
        self.assertEqual(editor.save(), "mia")
        self.assertEqual(load_persona("mia", self.app.personas_dir).tool_mode, "marker")


if __name__ == "__main__":
    unittest.main()
