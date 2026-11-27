"""Zertifikat-Pinning des HTTP-MCP-Clients: Geprüft wird der öffentliche Schlüssel, nicht das Zertifikat.
Ein neu ausgestelltes Zertifikat desselben Schlüssels (die App tut das bei neuen IP-Adressen) wird akzeptiert,
ein anderer Schlüssel nicht. Die Zertifikate erzeugt die openssl-Kommandozeile (ohne sie werden die Tests übersprungen)."""
import os
import shutil
import ssl
import subprocess
import tempfile
import threading
import unittest
from pathlib import Path
from unittest import mock

from chat_app.mcp_client import McpError
from chat_app.mcp_http import HttpMcpServer, pinned_key, spki_of_cert
from tests.test_http_mcp import TOKEN, FakeMcp

OPENSSL = shutil.which("openssl")


def _openssl(*args: str) -> None:
    subprocess.run([OPENSSL, *args], check=True, capture_output=True)


@unittest.skipUnless(OPENSSL, "openssl nicht gefunden")
class PinnedCertificateTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.dir = Path(tempfile.mkdtemp())
        cls.addClassCleanup(shutil.rmtree, cls.dir, True)
        for name in ("a", "b"):
            _openssl("ecparam", "-name", "prime256v1", "-genkey", "-noout", "-out", str(cls.dir / f"{name}.key"))

        def cert(key: str, out: str, san: str) -> str:
            _openssl("req", "-new", "-x509", "-key", str(cls.dir / f"{key}.key"), "-subj", "/CN=Test", "-days", "30",
                     "-addext", f"subjectAltName={san}", "-out", str(cls.dir / f"{out}.pem"))
            return str(cls.dir / f"{out}.pem")
        cls.cert_a = cert("a", "a1", "IP:127.0.0.1")
        cls.cert_a_reissued = cert("a", "a2", "IP:10.9.8.7")  # gleicher Schlüssel, andere Adresse
        cls.cert_b = cert("b", "b1", "IP:127.0.0.1")

    def setUp(self):
        self.fake = FakeMcp()
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.load_cert_chain(self.cert_a_reissued, str(self.dir / "a.key"))
        self.fake.socket = context.wrap_socket(self.fake.socket, server_side=True)
        threading.Thread(target=self.fake.serve_forever, daemon=True).start()
        self.addCleanup(self.fake.server_close)
        self.addCleanup(self.fake.shutdown)
        env = mock.patch.dict(os.environ, {"FAKE_TOKEN": TOKEN})
        env.start()
        self.addCleanup(env.stop)
        self.url = f"https://127.0.0.1:{self.fake.server_port}/mcp"

    def client(self, cert: str) -> HttpMcpServer:
        return HttpMcpServer(self.url, {"Authorization": "Bearer ${FAKE_TOKEN}"}, cert=cert, name="fake")

    def test_key_is_taken_from_the_certificate_not_from_its_other_fields(self):
        self.assertEqual(pinned_key(self.cert_a), pinned_key(self.cert_a_reissued))
        self.assertNotEqual(pinned_key(self.cert_a), pinned_key(self.cert_b))
        der = ssl.PEM_cert_to_DER_cert(Path(self.cert_a).read_text())
        self.assertEqual(spki_of_cert(der), pinned_key(self.cert_a))

    def test_reissued_certificate_with_the_same_key_is_accepted(self):
        server = self.client(self.cert_a)  # der Server zeigt a2 (andere Adresse, Name passt nicht), gepinnt ist a1
        server.start(timeout=5)
        self.addCleanup(server.close)
        self.assertEqual([tool["name"] for tool in server.list_tools()], ["send"])

    def test_another_key_is_rejected_before_the_token_is_sent(self):
        with self.assertRaises(McpError) as ctx:
            self.client(self.cert_b).start(timeout=5)
        self.assertIn("anderen Schlüssel", str(ctx.exception))
        self.assertEqual(self.fake.log, [])  # nichts (auch kein Token) hat den Server erreicht

    def test_file_without_certificate_is_reported(self):
        bad = self.dir / "leer.pem"
        bad.write_text("kein Zertifikat")
        with self.assertRaises(McpError) as ctx:
            self.client(str(bad)).start(timeout=5)
        self.assertIn("Zertifikat", str(ctx.exception))


if __name__ == "__main__":
    unittest.main()
