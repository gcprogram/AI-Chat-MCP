import unittest

from chat_app.llm_client import ChatClient, build_payload, dialect_for, is_openai_reasoning

MESSAGES = [{"role": "user", "content": "hi"}]
EXTRA = {"top_p": 0.9, "frequency_penalty": 0.3, "presence_penalty": 0.2, "repeat_penalty": 1.1}


class DialectTest(unittest.TestCase):
    def test_provider_is_recognised_from_the_url(self):
        self.assertEqual(dialect_for("https://api.openai.com/v1"), "openai")
        self.assertEqual(dialect_for("https://api.anthropic.com/v1"), "anthropic")
        self.assertEqual(dialect_for("https://generativelanguage.googleapis.com/v1beta/openai"), "gemini")
        self.assertEqual(dialect_for("http://192.168.0.10:1234/v1"), "standard")

    def test_own_servers_get_everything(self):
        p = build_payload("standard", "qwen3", MESSAGES, 0.7, 300, ["\nTom:"], EXTRA)
        for key in ("temperature", "max_tokens", "stream_options", "repeat_penalty", "stop"):
            self.assertIn(key, p)
        self.assertNotIn("max_completion_tokens", p)

    def test_openai_gets_max_completion_tokens_and_no_own_fields(self):
        p = build_payload("openai", "gpt-4o-mini", MESSAGES, 0.7, 300, ["x"], EXTRA)
        self.assertIn("max_completion_tokens", p)
        self.assertNotIn("max_tokens", p)
        self.assertNotIn("repeat_penalty", p)
        self.assertIn("temperature", p)

    def test_openai_reasoning_models_get_only_what_they_allow(self):
        for model in ("o3-mini", "o4-mini", "gpt-5", "gpt-5-mini", "ft:gpt-5-mini:org"):
            self.assertTrue(is_openai_reasoning(model), model)
            p = build_payload("openai", model, MESSAGES, 0.7, 300, ["x"], EXTRA)
            for key in ("temperature", "stop", "top_p", "frequency_penalty"):
                self.assertNotIn(key, p, model)
        for model in ("gpt-5-chat-latest", "gpt-4.1", "chatgpt-4o-latest", "gpt-4o"):
            self.assertFalse(is_openai_reasoning(model), model)

    def test_anthropic_and_gemini_keep_to_standard_fields(self):
        a = build_payload("anthropic", "claude-sonnet-5-5", MESSAGES, 0.7, 300, ["x"], EXTRA)
        self.assertEqual(set(a), {"model", "messages", "stream", "temperature", "max_tokens", "top_p", "stop"})
        g = build_payload("gemini", "gemini-2.5-flash", MESSAGES, 0.7, 300, None, EXTRA)
        self.assertNotIn("repeat_penalty", g)
        self.assertNotIn("stream_options", g)
        self.assertIn("frequency_penalty", g)

    def test_anthropic_headers(self):
        h = ChatClient("https://api.anthropic.com/v1", "k")._headers()
        self.assertEqual(h["x-api-key"], "k")
        self.assertEqual(h["anthropic-version"], "2023-06-01")
        self.assertNotIn("x-api-key", ChatClient("http://localhost:1234/v1", "k")._headers())


if __name__ == "__main__":
    unittest.main()
