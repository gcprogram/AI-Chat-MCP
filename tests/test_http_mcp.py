"""HTTP-Transport für MCP (Streamable HTTP): JSON- und SSE-Antworten, Sitzung, Anmeldung, Schutzregeln."""
import json
import os
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from unittest import mock

from chat_app.mcp_client import McpError
from chat_app.mcp_http import HttpMcpServer, check_url, expand_env, is_local_host
from chat_app.tools import ToolRegistry, find_call

TOKEN = "geheimer-test-token"
TOOLS = [{"name": "send", "description": "Sendet einen Code.",
          "inputSchema": {"type": "object", "properties": {"code": {"type": "string"}}, "required": ["code"]}}]


class FakeMcp(ThreadingHTTPServer):
    """Minimaler Streamable-HTTP-MCP-Server; sse=True antwortet als Ereignisstrom (mit vorgeschalteter Benachrichtigung)."""

    def __init__(self):
        super().__init__(("127.0.0.1", 0), _Handler)
        self.sse = False
        self.sessions: set[str] = set()
        self.log: list[tuple[str, dict, dict]] = []  # (HTTP-Methode, Header, JSON-Nachricht)
        self.sent_codes: list[str] = []
        self.redirect_to = ""
        self.counter = 0

    @property
    def url(self):
        return f"http://127.0.0.1:{self.server_port}/mcp"


class _Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def _headers(self):
        return {key.lower(): value for key, value in self.headers.items()}  # urllib schreibt Namen anders

    def _reply(self, status, body=b"", content_type="application/json", extra=None):
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        for key, value in (extra or {}).items():
            self.send_header(key, value)
        self.end_headers()
        self.wfile.write(body)

    def do_DELETE(self):
        self.server.log.append(("DELETE", self._headers(), {}))
        self.server.sessions.discard(self.headers.get("Mcp-Session-Id", ""))
        self._reply(200)

    def do_POST(self):
        server = self.server
        message = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        server.log.append(("POST", self._headers(), message))
        if server.redirect_to:
            self.send_response(302)
            self.send_header("Location", server.redirect_to)
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        if self.headers.get("Authorization") != f"Bearer {TOKEN}":
            return self._reply(401, b"no")
        method, extra = message.get("method"), {}
        if method == "initialize":
            server.counter += 1
            session = f"s{server.counter}"
            server.sessions.add(session)
            extra = {"Mcp-Session-Id": session}
            result = {"protocolVersion": "2025-03-26", "capabilities": {"tools": {}}, "serverInfo": {"name": "fake"}}
        elif self.headers.get("Mcp-Session-Id") not in server.sessions:
            return self._reply(404, b"unknown session")
        elif "id" not in message:
            return self._reply(202)
        elif method == "tools/list":
            result = {"tools": TOOLS}
        elif method == "tools/call":
            args = message["params"]["arguments"]
            server.sent_codes.append(args.get("code", ""))
            result = {"content": [{"type": "text", "text": f"gesendet {args.get('code')}"}]}
        else:
            return self._reply(200, json.dumps({"jsonrpc": "2.0", "id": message["id"],
                                                "error": {"code": -32601, "message": "unbekannt"}}).encode())
        reply = {"jsonrpc": "2.0", "id": message["id"], "result": result}
        if server.sse:
            events = [{"jsonrpc": "2.0", "method": "notifications/message", "params": {"data": "hallo"}}, reply]
            body = "".join(f"event: message\ndata: {json.dumps(e)}\n\n" for e in events).encode()
            return self._reply(200, body, "text/event-stream", extra)
        self._reply(200, json.dumps(reply).encode(), extra=extra)


class HttpMcpTest(unittest.TestCase):
    def setUp(self):
        self.fake = FakeMcp()
        threading.Thread(target=self.fake.serve_forever, daemon=True).start()
        self.addCleanup(self.fake.server_close)
        self.addCleanup(self.fake.shutdown)
        env = mock.patch.dict(os.environ, {"FAKE_TOKEN": TOKEN})
        env.start()
        self.addCleanup(env.stop)

    def server(self, **kw):
        server = HttpMcpServer(self.fake.url, {"Authorization": "Bearer ${FAKE_TOKEN}"}, name="fake", **kw)
        server.start(timeout=5)
        self.addCleanup(server.close)
        return server

    def test_handshake_tools_and_call_with_json_responses(self):
        server = self.server()
        self.assertEqual([t["name"] for t in server.list_tools()], ["send"])
        self.assertEqual(server.call_tool("send", {"code": "02"}), ("gesendet 02", False))
        methods = [m.get("method") for _, _, m in self.fake.log]
        self.assertEqual(methods[:3], ["initialize", "notifications/initialized", "tools/list"])
        later = self.fake.log[2][1]  # nach der Anmeldung: Sitzung und Protokollversion in jedem Header
        self.assertEqual((later["mcp-session-id"], later["mcp-protocol-version"]), ("s1", "2025-03-26"))
        self.assertNotIn("mcp-session-id", self.fake.log[0][1])

    def test_sse_responses_skip_notifications(self):
        self.fake.sse = True
        server = self.server()
        self.assertEqual(server.call_tool("send", {"code": "03"}), ("gesendet 03", False))

    def test_expired_session_is_renewed_once_transparently(self):
        server = self.server()
        self.fake.sessions.clear()  # Server neu gestartet
        self.assertEqual(server.call_tool("send", {"code": "01"})[0], "gesendet 01")
        self.assertEqual(self.fake.sent_codes, ["01"])  # genau einmal ausgeführt, nicht doppelt
        self.assertEqual([m.get("method") for _, _, m in self.fake.log].count("initialize"), 2)

    def test_close_ends_session(self):
        server = HttpMcpServer(self.fake.url, {"Authorization": "Bearer ${FAKE_TOKEN}"}, name="fake")
        server.start(timeout=5)
        server.close()
        self.assertEqual(self.fake.log[-1][0], "DELETE")
        self.assertEqual(self.fake.sessions, set())
        with self.assertRaises(McpError):
            server.request("ping")

    def test_wrong_token_and_server_error_are_clear(self):
        bad = HttpMcpServer(self.fake.url, {"Authorization": "Bearer falsch"}, name="fake")
        with self.assertRaises(McpError) as ctx:
            bad.start(timeout=5)
        self.assertIn("HTTP 401", str(ctx.exception))
        self.assertIn("Token", str(ctx.exception))
        server = self.server()
        with self.assertRaises(McpError) as ctx:
            server.request("gibt/es/nicht")
        self.assertIn("unbekannt", str(ctx.exception))

    def test_unreachable_server_raises_mcp_error(self):
        port = self.fake.server_port
        self.fake.shutdown()
        self.fake.server_close()
        with self.assertRaises(McpError) as ctx:
            HttpMcpServer(f"http://127.0.0.1:{port}/mcp", name="weg").start(timeout=2)
        self.assertIn("nicht erreichbar", str(ctx.exception))

    def test_redirect_is_not_followed_and_token_not_leaked(self):
        other = FakeMcp()
        threading.Thread(target=other.serve_forever, daemon=True).start()
        self.addCleanup(other.server_close)
        self.addCleanup(other.shutdown)
        self.fake.redirect_to = other.url
        with self.assertRaises(McpError):
            self.server()
        self.assertEqual(other.log, [])  # der andere Server hat nie etwas gesehen

    def test_missing_environment_variable_is_named_but_never_a_value(self):
        with mock.patch.dict(os.environ):
            os.environ.pop("NICHT_GESETZT_XYZ", None)
            with self.assertRaises(McpError) as ctx:
                HttpMcpServer(self.fake.url, {"Authorization": "Bearer ${NICHT_GESETZT_XYZ}"}).start()
        self.assertIn("NICHT_GESETZT_XYZ", str(ctx.exception))
        self.assertEqual(self.fake.log, [])  # es wurde gar nichts gesendet
        self.assertEqual(expand_env("Bearer ${FAKE_TOKEN}", "x"), f"Bearer {TOKEN}")

    def test_missing_certificate_file_is_reported(self):
        with self.assertRaises(McpError) as ctx:
            HttpMcpServer("https://127.0.0.1:1/mcp", cert="Z:/gibt/es/nicht.pem").start()
        self.assertIn("Zertifikat", str(ctx.exception))


class UrlRulesTest(unittest.TestCase):
    def test_http_only_for_local_addresses(self):
        for host in ("localhost", "127.0.0.1", "192.168.178.21", "10.0.0.5", "pc.local", "[::1]"):
            check_url(f"http://{host}:8080/mcp")
        for url in ("http://example.org/mcp", "http://8.8.8.8/mcp"):
            with self.assertRaises(McpError):
                check_url(url)
        check_url("https://example.org/mcp")  # https überall
        for bad in ("ftp://x/mcp", "mcp", "http:///mcp"):
            with self.assertRaises(McpError):
                check_url(bad)
        self.assertFalse(is_local_host("evil.example.org"))


class RegistryConfigTest(unittest.TestCase):
    def test_url_entry_with_aliases_and_header_validation(self):
        fake = FakeMcp()
        threading.Thread(target=fake.serve_forever, daemon=True).start()
        self.addCleanup(fake.server_close)
        self.addCleanup(fake.shutdown)
        with tempfile.TemporaryDirectory() as tmp, mock.patch.dict(os.environ, {"FAKE_TOKEN": TOKEN}):
            path = Path(tmp) / "mcp.json"
            path.write_text(json.dumps({
                "servers": {
                    "remote": {"url": fake.url, "headers": {"Authorization": "Bearer ${FAKE_TOKEN}"}, "raw": False},
                    "kaputt": {"url": fake.url, "headers": "nein"},
                    "aus": {"url": fake.url, "disabled": True}},
                "aliases": {"fan-low": {"server": "remote", "tool": "send", "args": {"code": "01"},
                                        "result": "discard", "status": "Fan: low"}}}), encoding="utf-8")
            registry = ToolRegistry()
            self.addCleanup(registry.shutdown)
            registry.start_from_config(path)
            self.assertEqual(registry.available(), ["fan-low"])
            self.assertEqual(len(registry.errors), 1)
            self.assertIn("headers", registry.errors[0])
            outcome = registry.run(find_call("[[mcp:fan-low]]"), ["fan-low"])
            self.assertEqual((outcome.ok, outcome.status), (True, "Fan: low"))
            self.assertEqual(fake.sent_codes, ["01"])
        registry.shutdown()
        self.assertEqual(fake.log[-1][0], "DELETE")  # beim Beenden wird auch die HTTP-Sitzung geschlossen


if __name__ == "__main__":
    unittest.main()
