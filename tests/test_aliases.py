"""Alias-Layer (Kurznamen mit festen Argumenten, Ergebnis-Modus, Bestätigung), Wetter- und Suche-Server."""
import gc
import json
import sys
import tempfile
import threading
import time
import unittest
from datetime import date
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from unittest import mock

from chat_app import chatlog
from chat_app.config import Config
from chat_app.context import CHAT, TOOL_RESULT, TOOL_SHOWN, Message
from chat_app.mcp_client import McpServer
from chat_app.persona import Persona, save_persona
from chat_app.session import ChatSession
from chat_app.tools import RESULT_DISCARD, RESULT_MODEL, RESULT_SHOW, ToolRegistry, find_call

ROOT = Path(__file__).resolve().parent.parent
DEMO = str(ROOT / "examples" / "demo_mcp_server.py")
sys.path.insert(0, str(ROOT / "servers"))

ALIASES = {
    "sieben": {"server": "demo", "tool": "calculate", "args": {"expression": "6*7"}, "result": "show",
               "hint": "Zeigt das Ergebnis von 6 mal 7", "confirm": False},
    "licht": {"server": "demo", "tool": "calculate", "args": {"expression": "1+1"}, "result": "discard",
              "status": "Licht an", "confirm": True},
    "rechne": {"server": "demo", "tool": "calculate"},
    "rechne-null": {"server": "demo", "tool": "calculate", "defaults": {"expression": "0+0"}},
    "uhr": {"server": "demo", "tool": "get_time", "result": "show", "remember": False},
}


def alias_registry(aliases=None):
    registry = ToolRegistry()
    registry.add_server("demo", McpServer("python", [DEMO], name="demo"), expose_raw=False)
    registry.add_aliases(ALIASES if aliases is None else aliases)
    return registry


class AliasTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.registry = alias_registry()

    @classmethod
    def tearDownClass(cls):
        cls.registry.shutdown()

    def run_call(self, text):
        return self.registry.run(find_call(text), self.registry.available())

    def test_raw_tools_hidden_but_aliases_registered(self):
        self.assertEqual(self.registry.available(), ["licht", "rechne", "rechne-null", "sieben", "uhr"])
        self.assertEqual(sorted(name for _, name in self.registry.remote), ["calculate", "get_time"])
        self.assertEqual(self.registry.errors, [])

    def test_fixed_arguments_and_modes(self):
        shown = self.run_call("[[mcp:sieben]]")
        self.assertEqual((shown.ok, shown.text, shown.mode, shown.remember), (True, "42", RESULT_SHOW, True))
        discarded = self.run_call("[[mcp:licht]]")
        self.assertEqual((discarded.mode, discarded.status, discarded.remember), (RESULT_DISCARD, "Licht an", False))
        self.assertFalse(self.run_call("[[mcp:uhr]]").remember)  # remember lässt sich abschalten
        self.assertEqual(self.run_call("[[mcp:rechne|2+3]]").mode, RESULT_MODEL)
        # feste Argumente gewinnen: ein Argument des Modells ändert nichts
        self.assertEqual(self.run_call("[[mcp:sieben|99+1]]").text, "42")

    def test_params_defaults_and_prompt(self):
        spec = self.registry.tools["rechne-null"]
        self.assertFalse(spec.params[0].required)
        self.assertEqual(self.run_call("[[mcp:rechne-null]]").text, "0")
        self.assertEqual(self.run_call("[[mcp:rechne-null|5*5]]").text, "25")
        self.assertFalse(self.run_call("[[mcp:rechne]]").ok)  # kein Standardwert -> Argument fehlt
        self.assertEqual(self.registry.tools["sieben"].params, [])
        section = self.registry.prompt_section(["sieben", "rechne"])
        self.assertIn("- sieben: Zeigt das Ergebnis von 6 mal 7. Example: [[mcp:sieben]]", section)
        self.assertIn("[[mcp:rechne|<expression>]]", section)

    def test_errors_always_go_to_the_model(self):
        outcome = self.run_call("[[mcp:rechne|1/0]]")
        self.assertEqual((outcome.ok, outcome.mode), (False, RESULT_MODEL))

    def test_invalid_aliases_are_reported_not_fatal(self):
        registry = alias_registry({
            "gut": {"server": "demo", "tool": "get_time"},
            "kein-server": {"server": "nope", "tool": "x"},
            "falsch": {"server": "demo", "tool": "calculate", "params": ["gibt-es-nicht"]},
            "modus": {"server": "demo", "tool": "get_time", "result": "laut"},
            "confirm": {"server": "demo", "tool": "get_time", "confirm": "ja"},
            "kaputt": "text"})
        self.addCleanup(registry.shutdown)
        self.assertEqual(registry.available(), ["gut"])
        self.assertEqual(len(registry.errors), 5)
        self.assertTrue(any("gibt-es-nicht" in e and "expression" in e for e in registry.errors))

    def test_config_file_with_aliases_and_raw_false(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "mcp.json"
            path.write_text(json.dumps({
                "mcpServers": {"demo": {"command": "python", "args": [DEMO], "raw": False}},
                "aliases": {"sieben": ALIASES["sieben"]}}), encoding="utf-8")
            registry = ToolRegistry()
            try:
                registry.start_from_config(path)
                self.assertEqual(registry.available(), ["sieben"])
                self.assertEqual(registry.errors, [])
            finally:
                registry.shutdown()


class ExampleConfigTest(unittest.TestCase):
    def test_example_config_loads_cleanly_with_remote_disabled(self):
        registry = ToolRegistry()
        try:
            registry.start_from_config(ROOT / "examples" / "mcp_servers.example.json", base_dir=ROOT)
            self.assertEqual(registry.errors, [])  # Aliase des deaktivierten Servers machen keinen Fehler
            self.assertEqual(registry.available(), ["calc", "time", "weather", "weather-past", "websearch"])
            for alias in registry.available():
                self.assertTrue(registry.tools[alias].description)
            section = registry.prompt_section(["weather", "weather-past", "websearch"])
            self.assertIn("[[mcp:weather|Berlin|tomorrow]]", section)
            self.assertIn("- weather: Weather forecast for today or tomorrow.", section)
        finally:
            registry.shutdown()


class ShownHistoryTest(unittest.TestCase):
    def make_session(self):
        session = ChatSession(Config(user_name="Alex"), Persona(name="Mia"), ToolRegistry())
        session.add_user("Wie wird das Wetter?")
        session.add_assistant("Ich schaue nach. [[mcp:wetter]]")
        session.add_tool_shown("wetter", "06:00 sonnig, 10°C")
        return session

    def test_shown_result_travels_with_next_user_message_and_roles_alternate(self):
        session = self.make_session()
        session.add_user("Und was ziehe ich an?")
        messages = session.request_messages()
        self.assertEqual([m["role"] for m in messages], ["system", "user", "assistant", "user"])
        last = messages[-1]["content"]
        self.assertIn("[Ergebnis von wetter, Alex schon angezeigt]\n06:00 sonnig, 10°C", last)
        self.assertTrue(last.endswith("Und was ziehe ich an?"))
        self.assertNotIn("Antworte Alex jetzt", last)  # keine Aufforderung, auf das Ergebnis zu antworten

    def test_loading_drops_trailing_shown_result_and_json_roundtrip(self):
        session = self.make_session()
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "chat.json"
            chatlog.save_json(path, session.history, "mia", "Alex")
            loaded = chatlog.load_chat(path, {"Alex"}, {"Mia"})
        self.assertEqual([m.kind for m in loaded.messages], [CHAT, CHAT, TOOL_SHOWN])
        self.assertEqual(session.load(loaded.messages), "")
        self.assertEqual([m.kind for m in session.history], [CHAT, CHAT])  # verwaistes Ergebnis entfernt


def sse(text):
    chunks = [{"choices": [{"delta": {"content": text}}]}, {"choices": [], "usage": {"prompt_tokens": 50}}]
    return ("".join(f"data: {json.dumps(c, ensure_ascii=False)}\n\n" for c in chunks) + "data: [DONE]\n\n").encode()


REQUESTS = []


class _Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        REQUESTS.append(body)
        if body["messages"][-1]["content"].startswith(("[Ergebnis von", "[Fehler bei")):
            reply = "Dann eben nicht."
        else:
            reply = {"show": "Ich schaue nach. [[mcp:sieben]] erfunden", "discard": "Mache ich. [[mcp:licht]]"}.get(
                body["model"], "Gern geschehen.")
        data = sse(reply)
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)


class GuiAliasTest(unittest.TestCase):
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
        save_persona(Persona(name="Mia", greeting="Hallo!", tools=["sieben", "licht"]), root / "personas")
        self.registry = alias_registry()
        self.addCleanup(self.registry.shutdown)
        self.tk = tk.Tk()
        self.tk.withdraw()
        self.addCleanup(self.tk.destroy)
        cfg = Config(base_url=self.url, model="show", user_name="Alex", persona="mia", confirm_tools=False)
        self.app = ChatApp(self.tk, config=cfg, config_path=root / "config.json", personas_dir=root / "personas",
                           chats_dir=root / "chats", registry=self.registry)
        self.addCleanup(gc.collect)  # Tk-Variablen im Hauptthread freigeben, bevor Tk zerstört wird (läuft vor destroy)
        REQUESTS.clear()

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

    def test_show_mode_prints_result_without_second_model_round_and_model_sees_it_later(self):
        self.ask("Was ist 6 mal 7?", "show")
        self.assertEqual(len(REQUESTS), 1)  # keine zweite Runde: das Ergebnis geht nicht erst durchs Modell
        self.assertEqual([(m.role, m.kind) for m in self.app.session.history],
                         [("user", CHAT), ("assistant", CHAT), ("user", TOOL_SHOWN)])
        text = self.app.chat.get("1.0", "end")
        self.assertIn("» Werkzeug sieben", text)
        self.assertIn("\n42\n", text)
        self.assertNotIn("erfunden", text)

        self.ask("Danke dir", "plain")
        self.assertEqual([m["role"] for m in REQUESTS[1]["messages"]], ["system", "user", "assistant", "user"])
        last = REQUESTS[1]["messages"][-1]["content"]
        self.assertIn("schon angezeigt]\n42", last)
        self.assertTrue(last.endswith("Danke dir"))

    def test_discard_mode_prints_status_only_and_confirm_is_per_alias(self):
        self.app.config.confirm_tools = False
        with mock.patch("chat_app.gui.messagebox.askyesno", return_value=True) as ask:
            self.ask("Licht an", "discard")
        self.assertEqual(ask.call_count, 1)  # Alias verlangt Bestätigung, obwohl global aus
        self.assertEqual(len(REQUESTS), 1)
        self.assertEqual([m.kind for m in self.app.session.history], [CHAT, CHAT])  # nichts gemerkt
        self.assertIn("✔ Licht an", self.app.chat.get("1.0", "end"))
        self.assertNotIn("2", self.app.chat.get("1.0", "end").split("✔")[1])  # Ergebnis "2" verworfen

        self.app.session.reset()
        self.app.config.confirm_tools = True
        with mock.patch("chat_app.gui.messagebox.askyesno", return_value=True) as ask:
            self.ask("Was ist 6 mal 7?", "show")
        self.assertEqual(ask.call_count, 0)  # Alias sagt confirm=false, obwohl global an

    def test_declined_confirmation_goes_back_to_model(self):
        with mock.patch("chat_app.gui.messagebox.askyesno", return_value=False):
            self.ask("Licht an", "discard")
        self.assertEqual(len(REQUESTS), 2)  # Ablehnung wird dem Modell gemeldet
        self.assertIn("abgelehnt", REQUESTS[1]["messages"][-1]["content"])

    def test_marker_for_tool_not_enabled_for_companion_is_reported_not_silent(self):
        self.app.session.persona.tools = []  # "sieben" existiert, der Companion hat aber keine Werkzeuge freigeschaltet
        self.ask("Was ist 6 mal 7?", "show")
        text = self.app.chat.get("1.0", "end")
        self.assertIn("'sieben' ist für Mia nicht freigeschaltet", text)
        self.assertNotIn("\n42\n", text)  # nichts ausgeführt
        self.assertEqual(len(REQUESTS), 1)


class WeatherServerTest(unittest.TestCase):
    def test_parse_date_variants(self):
        from weather_server import parse_date

        today = date(2026, 11, 1)
        self.assertEqual(parse_date("yesterday", today), date(2026, 10, 31))
        self.assertEqual(parse_date("gestern", today), date(2026, 10, 31))  # deutsche Wörter gehen auch
        self.assertEqual(parse_date("Day before Yesterday", today), date(2026, 10, 30))
        self.assertEqual(parse_date("", today), date(2026, 10, 31))
        self.assertEqual(parse_date("2020-11-25", today), date(2020, 11, 25))
        self.assertEqual(parse_date("25.11.2020", today), date(2020, 11, 25))
        self.assertEqual(parse_date("25.11.20", today), date(2020, 11, 25))
        self.assertEqual(parse_date("25.11.", today), date(2025, 11, 25))  # sonst läge es in der Zukunft
        self.assertEqual(parse_date("24.10.", today), date(2026, 10, 24))
        from mini_mcp import ToolError
        for bad in ("recently", "31.02.2020", "2020-13-01", "31.02."):
            with self.assertRaises(ToolError):
                parse_date(bad, today)

    def test_pick_place_uses_region_hint(self):
        from weather_server import pick_place

        results = [{"name": "Langen", "admin1": "Lower Saxony", "country": "Germany"},
                   {"name": "Langen", "admin1": "Hesse", "country": "Germany"}]
        self.assertEqual(pick_place(results, " Hesse")["admin1"], "Hesse")
        self.assertEqual(pick_place(results, "")["admin1"], "Lower Saxony")  # ohne Zusatz: erster Treffer
        self.assertEqual(pick_place(results, "Bavaria")["admin1"], "Lower Saxony")  # Zusatz passt nirgends

    def test_forecast_and_historical_text(self):
        from weather_server import forecast_text, historical_text

        hours = [{"time": str(h * 100), "tempC": str(h), "FeelsLikeC": str(h - 1), "chanceofrain": "10",
                  "winddirDegree": "225", "windspeedKmph": "7", "weatherDesc": [{"value": "Sunny "}]}
                 for h in range(0, 24, 3)]
        data = {"nearest_area": [{"areaName": [{"value": "Berlin"}], "region": [{"value": "Berlin"}],
                                  "country": [{"value": "Germany"}]}],
                "weather": [{"date": "2026-11-01", "mintempC": "3", "maxtempC": "12", "hourly": hours}]}
        text = forecast_text(data, 0, 6, 15, 3)
        lines = text.splitlines()
        self.assertEqual(lines[0], "Weather for Berlin, Germany, Sunday, 2026-11-01: 3 to 12°C")
        self.assertEqual([l[:5] for l in lines[1:]], ["06:00", "09:00", "12:00"])
        self.assertIn("Sunny, 6°C (feels like 5°C), rain 10%, wind SW 7 km/h", lines[1])
        with self.assertRaises(Exception):
            forecast_text(data, 1, 6, 15, 3)  # morgen fehlt

        daily = {"temperature_2m_max": [6.1], "temperature_2m_min": [0.3], "precipitation_sum": [0.0], "weathercode": [3],
                 "windspeed_10m_max": [10.7], "winddirection_10m_dominant": [157]}
        text = historical_text("Langen", date(2020, 11, 25), daily)
        self.assertIn("on Wednesday, 2020-11-25: overcast", text)
        self.assertIn("High 6.1°C, low 0.3°C", text)
        self.assertIn("Wind up to 10.7 km/h from SSE", text)
        with self.assertRaises(Exception):
            historical_text("Langen", date(2026, 11, 1), {"temperature_2m_max": [None]})

    def test_server_over_stdio_reports_errors_without_network(self):
        server = McpServer("python", [str(ROOT / "servers" / "weather_server.py")], cwd=str(ROOT), name="weather")
        server.start()
        self.addCleanup(server.close)
        self.assertEqual([t["name"] for t in server.list_tools()], ["forecast", "historical"])
        text, is_error = server.call_tool("historical", {"place": "Berlin", "date": "recently"})
        self.assertTrue(is_error)
        self.assertIn("not understood", text)
        self.assertTrue(server.call_tool("forecast", {"place": " "})[1])


class SearchServerTest(unittest.TestCase):
    PAGE = """
    <div class="result"><h2 class="result__title"><a rel="nofollow" class="result__a"
      href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.org%2Fa%3Fx%3D1&amp;rut=abc">Titel <b>A</b> &amp; Co</a></h2>
      <a class="result__snippet" href="x">Auszug mit <b>Fett</b> und &quot;Zitat&quot;</a></div>
    <div class="result"><h2><a class="result__a" href="https://example.org/b">Titel B</a></h2></div>
    <div class="result"><h2><a class="result__a" href="https://example.org/c">Titel C</a></h2>
      <a class="result__snippet" href="y">Auszug C</a></div>
    """

    def test_parse_and_format(self):
        from websearch_server import format_results, parse_results

        results = parse_results(self.PAGE, 5)
        self.assertEqual(results[0], ("Titel A & Co", "https://example.org/a?x=1", 'Auszug mit Fett und "Zitat"'))
        self.assertEqual(results[1], ("Titel B", "https://example.org/b", ""))  # Snippet gehört nicht zum Nachbarn
        self.assertEqual(results[2][2], "Auszug C")
        self.assertEqual(len(parse_results(self.PAGE, 2)), 2)
        self.assertTrue(format_results(results).startswith("1. Titel A & Co (https://example.org/a?x=1)\n   Auszug"))


if __name__ == "__main__":
    unittest.main()
