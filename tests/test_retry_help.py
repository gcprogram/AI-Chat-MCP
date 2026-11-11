import unittest

from chat_app.config import Config
from chat_app.persona import Persona
from chat_app.session import ChatSession
from chat_app.tools import Param, ToolRegistry, ToolSpec, is_retryable_error, result_message


def make_session():
    registry = ToolRegistry()
    registry.tools["todo-add"] = ToolSpec("todo-add", "x", "x", "Adds a to-do", [Param("text", "string", True)], "[[mcp:todo-add|Buy milk]]")
    registry.tools["todo-list"] = ToolSpec("todo-list", "x", "x", "Lists the to-dos", [], "[[mcp:todo-list]]")
    session = ChatSession(Config(user_name="Tom"), Persona(name="T", tools=["todo-add", "todo-list"]), registry)
    session.add_user("Füge der Liste zu: Milch")
    session.add_assistant("[[mcp:todo-update|Milch]]")
    return session


class RetryHelpTest(unittest.TestCase):
    def test_unknown_tool_lists_the_tools_and_asks_for_a_correction(self):
        session = make_session()
        session.add_tool_result("todo-update", "Unbekanntes Werkzeug 'todo-update'. Verfügbar: todo-add, todo-list.", False)
        last = session._wire_history()[-1].content
        self.assertTrue(last.startswith("[Error from tool call todo-update] Unbekanntes Werkzeug"), last)
        self.assertIn("- todo-add: Adds a to-do. Example: [[mcp:todo-add|Buy milk]]", last)
        self.assertIn("Do not apologize", last)
        self.assertNotIn("Sage", last)

    def test_other_errors_and_old_turns_keep_the_short_form(self):
        session = make_session()
        session.add_tool_result("todo-add", "Server kaputt", False)
        self.assertTrue(session._wire_history()[-1].content.startswith("[Fehler bei todo-add] Server kaputt"))
        old = make_session()
        old.add_tool_result("todo-update", "Unbekanntes Werkzeug 'todo-update'. Verfügbar: todo-add.", False)
        old.add_assistant("Das geht nicht.")
        old.add_user("Neue Frage")
        first = next(m for m in old._wire_history() if m.is_tool)
        self.assertNotIn("Tools you can call", first.content)

    def test_retryable_errors(self):
        self.assertTrue(is_retryable_error("Es fehlt: text. Richtiges Format: [[mcp:todo-add|<text>]]"))
        self.assertTrue(is_retryable_error("Unbekannte Taste 'P99'. Erlaubt: P1."))
        self.assertFalse(is_retryable_error("Calendar access is not allowed."))
        self.assertEqual("[Ergebnis von x]\nok\n[Ende des Ergebnisses]\nAntworte Tom jetzt kurz und in deinen eigenen Worten anhand dieses Ergebnisses. "
                         "Schreibe keinen neuen Werkzeugaufruf, außer dir fehlt noch eine Information.", result_message("x", "ok", True, "Tom"))


if __name__ == "__main__":
    unittest.main()
