import json
import unittest
from unittest import mock

from chat_app import server_import
from chat_app.mcp_client import McpClient
from chat_app.tools import ToolRegistry, find_call

BUTTONS = [{"code": 1, "hex": "01", "label": "P1"}, {"code": 2, "hex": "02", "label": "P2"},
           {"code": 11, "hex": "0b", "label": "Wave"}, {"code": 17, "hex": "11"}]
TOOLS = [
    {"name": "status", "description": "Reports the state."},
    {"name": "buttons", "description": "Lists the buttons."},
    {"name": "send", "description": "Sends a code.", "inputSchema": {"type": "object", "properties": {"code": {"type": "string"}}, "required": ["code"]}},
    {"name": "off", "description": "Switches off. More text."},
]


class FakeRemote(McpClient):
    name = "remote"

    def __init__(self, buttons=BUTTONS):
        self.buttons, self.sent, self.button_calls = buttons, [], 0

    def start(self, timeout=30.0):
        pass

    def close(self):
        pass

    def list_tools(self, timeout=30.0):
        return TOOLS

    def call_tool(self, name, arguments, timeout=60.0):
        if name == "buttons":
            self.button_calls += 1
            return json.dumps(self.buttons), False
        self.sent.append((name, arguments.get("code", "")))
        return f"ok {name}", name == "fail"


def registry(aliases, buttons=BUTTONS):
    fake = FakeRemote(buttons)
    reg = ToolRegistry()
    reg.add_server("remote", fake, expose_raw=False)
    reg.add_aliases(aliases)
    return reg, fake


class ButtonAliasTest(unittest.TestCase):
    def test_button_by_label_needs_only_server_and_label(self):
        reg, fake = registry({"tv-p1": {"server": "remote", "button": "P1"}, "tv-wave": {"server": "remote", "button": "wave"},
                              "tv-17": {"server": "remote", "button": "0x11"}, "tv-off": {"server": "remote", "tool": "off"}})
        self.assertEqual([], reg.errors)
        self.assertEqual(1, fake.button_calls)  # die Tastenliste wird nur einmal gelesen
        out = reg.run(find_call("[[mcp:tv-p1]]"), ["tv-p1"])
        self.assertEqual((True, "discard", "P1"), (out.ok, out.mode, out.status))
        reg.run(find_call("[[mcp:tv-wave]]"), ["tv-wave"])
        reg.run(find_call("[[mcp:tv-17]]"), ["tv-17"])
        self.assertEqual([("send", "0x01"), ("send", "0x0b"), ("send", "0x11")], fake.sent)

    def test_unknown_button_names_the_known_ones(self):
        reg, _ = registry({"tv-p9": {"server": "remote", "button": "P9"}})
        self.assertEqual([], reg.available())
        self.assertIn("P9", reg.errors[0])
        self.assertIn("p1", reg.errors[0])
        self.assertIn("wave", reg.errors[0])

    def test_server_without_buttons_tool_is_reported(self):
        fake = FakeRemote()
        reg = ToolRegistry()
        with mock.patch.object(FakeRemote, "list_tools", return_value=[TOOLS[2]]):
            reg.add_server("remote", fake, expose_raw=False)
        reg.add_aliases({"x": {"server": "remote", "button": "P1"}})
        self.assertIn("kein Werkzeug 'buttons'", reg.errors[0])


class MacroTest(unittest.TestCase):
    def test_macro_plays_calls_and_waits(self):
        reg, fake = registry({"tv-p1": {"server": "remote", "button": "P1"}, "tv-off": {"server": "remote", "tool": "off"},
                              "show": {"macro": ["tv-p1", 3, {"wait": 0.5}, "tv-off"], "hint": "Short demo"}})
        self.assertEqual([], reg.errors)
        with mock.patch("chat_app.tools.time.sleep") as sleep:
            out = reg.run(find_call("[[mcp:show]]"), ["show"])
        self.assertTrue(out.ok)
        self.assertEqual([mock.call(3.0), mock.call(0.5)], sleep.call_args_list)
        self.assertEqual([("send", "0x01"), ("off", "")], fake.sent)
        self.assertEqual("discard", out.mode)
        self.assertIn("tv-p1: ok send", out.text)
        self.assertIn("wartet 3 s", out.text)

    def test_macro_with_an_unknown_step_is_dropped(self):
        reg, fake = registry({"a": {"server": "remote", "tool": "fail"}, "b": {"server": "remote", "tool": "off"},
                              "m": {"macro": ["a", "b"]}})
        # "fail" kennt der Schein-Server nicht als Werkzeug der Liste: Alias fehlt, Makro wird verworfen
        self.assertIn("Alias 'm': 'a' gibt es nicht", " ".join(reg.errors))
        self.assertNotIn("m", reg.available())

    def test_macro_calling_a_skipped_server_disappears_without_an_error(self):
        fake = FakeRemote()
        reg = ToolRegistry()
        reg.add_server("remote", fake, expose_raw=False)
        reg.add_aliases({"on": {"server": "remote", "tool": "off"}, "tv": {"server": "aus", "button": "P1"},
                         "m": {"macro": ["on", "tv"]}}, frozenset({"aus"}))
        self.assertEqual([], reg.errors)
        self.assertEqual(["on"], reg.available())

    def test_macro_validation(self):
        reg, _ = registry({"off": {"server": "remote", "tool": "off"},
                           "m1": {"macro": []}, "m2": {"macro": [{"wait": 99}, "off"]}, "m3": {"macro": [3]},
                           "m4": {"macro": ["off", "m5"]}, "m5": {"macro": ["off"]}, "m6": {"macro": ["nix"]}})
        self.assertEqual(["m5", "off"], reg.available())
        errors = " | ".join(reg.errors)
        for fragment in ("m1", "m2", "m3", "m4", "m6"):
            self.assertIn(f"Alias '{fragment}'", errors)
        self.assertIn("selbst ein Makro", errors)

    def test_macro_error_in_a_step_is_reported_with_its_number(self):
        reg, fake = registry({"off": {"server": "remote", "tool": "off"}, "m": {"macro": ["off", 1, "off"]}})
        fake.call_tool = lambda name, arguments, timeout=60.0: ("kaputt", True)
        with mock.patch("chat_app.tools.time.sleep"):
            out = reg.run(find_call("[[mcp:m]]"), ["m"])
        self.assertEqual((False, "Schritt 1 (off): kaputt"), (out.ok, out.text))


class ServerImportTest(unittest.TestCase):
    def test_remote_gets_one_short_entry_per_button(self):
        entries = server_import.alias_entries("remote", TOOLS, BUTTONS, "tv-")
        self.assertEqual(["tv-p1", "tv-p2", "tv-wave", "tv-0x11", "tv-status", "tv-off"], list(entries))
        self.assertEqual({"server": "remote", "button": "P1", "hint": "Press P1"}, entries["tv-p1"])
        self.assertEqual("0x11", entries["tv-0x11"]["button"])  # Taste ohne Beschriftung: der Code
        self.assertEqual("discard", entries["tv-off"]["result"])
        self.assertEqual("Switches off.", entries["tv-off"]["hint"])

    def test_plain_server_gets_one_entry_per_tool(self):
        entries = server_import.alias_entries("demo", [{"name": "get_time", "description": "Returns the time."}], None, "demo-")
        self.assertEqual({"demo-get-time": {"server": "demo", "tool": "get_time", "hint": "Returns the time."}}, entries)

    def test_merge_keeps_existing_aliases_and_hides_raw_tools(self):
        data = {"servers": {"remote": {"url": "https://x/mcp"}}, "aliases": {"tv-p1": {"server": "remote", "tool": "send", "args": {"code": "0x99"}}}}
        added, skipped = server_import.merge(data, "remote", TOOLS, BUTTONS, "tv-")
        self.assertEqual(["tv-p1"], skipped)
        self.assertNotIn("tv-p1", added)
        self.assertEqual("0x99", data["aliases"]["tv-p1"]["args"]["code"])  # nicht überschrieben
        self.assertIs(False, data["servers"]["remote"]["raw"])
        self.assertIn("tv-wave", data["aliases"])

    def test_imported_config_loads_into_a_registry(self):
        data = {"aliases": {}}
        server_import.merge(data, "remote", TOOLS, BUTTONS, "tv-")
        reg = ToolRegistry()
        reg.add_server("remote", FakeRemote(), expose_raw=False)
        reg.add_aliases(data["aliases"])
        self.assertEqual([], reg.errors)
        self.assertEqual(["tv-0x11", "tv-off", "tv-p1", "tv-p2", "tv-status", "tv-wave"], reg.available())


if __name__ == "__main__":
    unittest.main()
