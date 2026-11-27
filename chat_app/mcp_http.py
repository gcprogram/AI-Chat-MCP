"""MCP-Client über HTTP (Streamable HTTP): JSON-RPC per POST, die Antwort kommt als JSON oder als SSE-Strom.

Nur Standardbibliothek. Gedacht für MCP-Server im Heimnetz oder im Internet, z. B. die Fernbedienungs-App auf dem
Handy (ohne stdio-Bridge). Schutz der Zugangsdaten:
- Header-Werte dürfen `${UMGEBUNGSVARIABLE}` enthalten (der Token steht so nie in der Konfigurationsdatei);
- `http://` ist nur für lokale Adressen erlaubt (localhost, Heimnetz, `.local`), sonst `https://`;
- ein selbst signiertes Zertifikat wird über `cert` angegeben (nichts landet im Zertifikatsspeicher des Systems).
  Geprüft wird der öffentliche Schlüssel des Zertifikats (Pinning), nicht das Zertifikat selbst: Die App stellt
  das Zertifikat neu aus, wenn sich die IP-Adressen des Handys ändern, der Schlüssel bleibt gleich;
- Weiterleitungen werden nicht verfolgt (sonst ginge der Token an einen fremden Host)."""
from __future__ import annotations

import http.client
import ipaddress
import json
import logging
import os
import re
import ssl
import threading
import time
import urllib.error
import urllib.request
from itertools import count
from urllib.parse import urlparse

from .mcp_client import McpClient, McpError

log = logging.getLogger(__name__)
HTTP_PROTOCOL_VERSION = "2025-03-26"  # erste Version mit Streamable HTTP; der Server nennt die tatsächlich genutzte
MAX_RESPONSE_BYTES = 2_000_000
_ENV_RE = re.compile(r"\$\{(\w+)\}")


class _SessionLost(McpError):
    """Der Server kennt die Sitzung nicht mehr (HTTP 404 auf eine Anfrage mit Sitzungs-ID)."""


def _der_item(data: bytes, pos: int) -> tuple[int, int, int]:
    """Liest den Kopf eines DER-Elements: (Tag, Beginn des Inhalts, Ende des Elements)."""
    tag, length, start = data[pos], data[pos + 1], pos + 2
    if length & 0x80:
        count = length & 0x7F
        if count == 0 or count > 4:
            raise ValueError("DER-Länge nicht unterstützt")
        length, start = int.from_bytes(data[pos + 2:pos + 2 + count], "big"), pos + 2 + count
    if start + length > len(data):
        raise ValueError("DER-Element abgeschnitten")
    return tag, start, start + length


def spki_of_cert(der: bytes) -> bytes:
    """Der öffentliche Schlüssel (SubjectPublicKeyInfo, DER) eines X.509-Zertifikats."""
    try:
        _, body, _ = _der_item(der, 0)  # Certificate
        _, pos, _ = _der_item(der, body)  # tbsCertificate
        tag, _, end = _der_item(der, pos)
        if tag == 0xA0:  # optionale Version
            pos = end
        for _ in range(5):  # Seriennummer, Signatur, Aussteller, Gültigkeit, Inhaber
            pos = _der_item(der, pos)[2]
        tag, _, end = _der_item(der, pos)
        if tag != 0x30:
            raise ValueError("kein SubjectPublicKeyInfo")
        return der[pos:end]
    except IndexError as exc:
        raise ValueError("Zertifikat nicht lesbar") from exc


def pinned_key(path: str) -> bytes:
    """Liest eine PEM-Datei und liefert den öffentlichen Schlüssel ihres (ersten) Zertifikats."""
    with open(os.path.expanduser(path), encoding="ascii", errors="replace") as handle:
        match = re.search(r"-----BEGIN CERTIFICATE-----.+?-----END CERTIFICATE-----", handle.read(), re.S)
    if not match:
        raise ValueError("kein Zertifikat (PEM) in der Datei")
    return spki_of_cert(ssl.PEM_cert_to_DER_cert(match[0]))


class _PinnedConnection(http.client.HTTPSConnection):
    """HTTPS-Verbindung, die nur zu dem Server hält, dessen Zertifikat den angegebenen Schlüssel trägt.
    Die Prüfung läuft in connect(), also bevor irgendetwas (auch der Token) gesendet wird; dass der Server den
    zugehörigen privaten Schlüssel besitzt, beweist der TLS-Handshake selbst."""
    pinned_spki = b""

    def connect(self) -> None:
        super().connect()
        try:
            actual = spki_of_cert(self.sock.getpeercert(binary_form=True) or b"")  # type: ignore[union-attr]
        except ValueError:
            actual = b""
        if actual != self.pinned_spki:
            self.close()
            raise ssl.SSLError("Schlüssel des Servers stimmt nicht mit dem Zertifikat in 'cert' überein")


class _PinnedHandler(urllib.request.HTTPSHandler):
    def __init__(self, spki: bytes):
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        context.minimum_version = ssl.TLSVersion.TLSv1_2
        context.check_hostname = False  # der Schlüssel ersetzt Name und Kette
        context.verify_mode = ssl.CERT_NONE
        super().__init__(context=context)
        self._spki = spki

    def https_open(self, req):
        spki = self._spki

        def connection(host, **kwargs):
            conn = _PinnedConnection(host, **kwargs)
            conn.pinned_spki = spki
            return conn
        return self.do_open(connection, req, context=self._context)


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None  # -> HTTPError statt blindem Folgen (Authorization-Header würde mitgehen)


def expand_env(text: str, what: str) -> str:
    def replace(match: re.Match) -> str:
        value = os.environ.get(match[1])
        if not value:
            raise McpError(f"Umgebungsvariable {match[1]} ist nicht gesetzt (nötig für {what}).")
        return value
    return _ENV_RE.sub(replace, text)


def is_local_host(host: str) -> bool:
    host = host.lower()
    if host == "localhost" or host.endswith((".localhost", ".local")):
        return True
    try:
        address = ipaddress.ip_address(host)
    except ValueError:
        return False
    return address.is_loopback or address.is_private or address.is_link_local


def check_url(url: str) -> None:
    parts = urlparse(url)
    if parts.scheme not in ("http", "https") or not parts.hostname:
        raise McpError(f"Ungültige Server-URL '{url}' (erwartet http:// oder https://).")
    if parts.scheme == "http" and not is_local_host(parts.hostname):
        raise McpError(f"'{url}': http:// ist nur für lokale Adressen erlaubt, sonst https:// verwenden.")


class HttpMcpServer(McpClient):
    def __init__(self, url: str, headers: dict[str, str] | None = None, cert: str | None = None, name: str = "mcp"):
        self.name, self.url = name, url
        self.headers, self.cert = dict(headers or {}), cert
        self.session_id: str | None = None
        self.protocol = HTTP_PROTOCOL_VERSION
        self._ids = count(1)
        self._opener: urllib.request.OpenerDirector | None = None
        self._resolved: dict[str, str] = {}
        self._ready = False
        self._lock = threading.Lock()  # Anmeldung und Neuanmeldung nicht gleichzeitig

    # --- Lebenszyklus --------------------------------------------------------------------------

    def start(self, timeout: float = 30.0) -> None:
        check_url(self.url)
        self._resolved = {key: expand_env(str(value), f"den Header '{key}'") for key, value in self.headers.items()}
        handlers: list = [_NoRedirect]
        if self.cert:
            try:
                handlers.append(_PinnedHandler(pinned_key(self.cert)))
            except (OSError, ValueError, ssl.SSLError) as exc:
                raise McpError(f"Zertifikat '{self.cert}' nicht lesbar: {exc}") from exc
        self._opener = urllib.request.build_opener(*handlers)
        try:
            self._handshake(timeout)
        except Exception:
            self.close()
            raise

    def close(self) -> None:
        opener, session, self._opener, self._ready = self._opener, self.session_id, None, False
        self.session_id = None
        if opener is None or not session:
            return
        try:  # Sitzung beenden; Server dürfen das ablehnen (405), egal
            request = urllib.request.Request(self.url, headers={**self._resolved, "Mcp-Session-Id": session},
                                             method="DELETE")
            opener.open(request, timeout=3).close()
        except (OSError, ValueError):
            pass

    def _handshake(self, timeout: float) -> None:
        with self._lock:
            self.session_id, self._ready = None, False
            reply = self._post({"jsonrpc": "2.0", "id": next(self._ids), "method": "initialize", "params": {
                "protocolVersion": HTTP_PROTOCOL_VERSION, "capabilities": {},
                "clientInfo": {"name": "ai-chat-mcp", "version": "1.0"}}}, timeout)
            result = self._result(reply, "initialize")
            if isinstance(result.get("protocolVersion"), str):
                self.protocol = result["protocolVersion"]
            self._ready = True  # ab hier mit MCP-Protocol-Version-Header
            self._post({"jsonrpc": "2.0", "method": "notifications/initialized"}, timeout)

    # --- Protokoll -----------------------------------------------------------------------------

    def request(self, method: str, params: dict | None = None, timeout: float = 30.0) -> dict:
        if self._opener is None:
            raise McpError(f"Server '{self.name}' ist nicht gestartet.")
        message = {"jsonrpc": "2.0", "id": next(self._ids), "method": method, "params": params or {}}
        try:
            reply = self._post(message, timeout)
        except _SessionLost:
            self._handshake(timeout)  # Sitzung abgelaufen (Server neu gestartet): einmal neu anmelden
            reply = self._post(message, timeout)
        return self._result(reply, method)

    @staticmethod
    def _result(reply: dict | None, method: str) -> dict:
        reply = reply or {}
        if "error" in reply:
            error = reply["error"]
            raise McpError(error.get("message", str(error)) if isinstance(error, dict) else str(error))
        return reply.get("result") or {}

    def _post(self, message: dict, timeout: float) -> dict | None:
        """Sendet eine Nachricht. Anfragen (mit id) liefern die passende Antwort, Benachrichtigungen None."""
        headers = {**self._resolved, "Content-Type": "application/json",
                   "Accept": "application/json, text/event-stream"}
        sent_session = self.session_id
        if sent_session:
            headers["Mcp-Session-Id"] = sent_session
        if self._ready:
            headers["MCP-Protocol-Version"] = self.protocol
        request = urllib.request.Request(self.url, json.dumps(message, ensure_ascii=False).encode("utf-8"), headers,
                                         method="POST")
        try:
            with self._opener.open(request, timeout=timeout) as response:  # type: ignore[union-attr]
                if message.get("method") == "initialize" and response.headers.get("Mcp-Session-Id"):
                    self.session_id = response.headers["Mcp-Session-Id"]
                if "id" not in message:
                    return None  # Benachrichtigung: 202 ohne Inhalt
                return self._read_reply(response, message["id"], timeout)
        except urllib.error.HTTPError as exc:
            body = exc.read(4096)
            try:  # manche Server melden Fehler als JSON-RPC-Antwort mit HTTP-Fehlercode
                parsed = json.loads(body.decode("utf-8", errors="replace"))
                if isinstance(parsed, dict) and "error" in parsed:
                    return parsed
            except ValueError:
                pass
            if exc.code == 404 and sent_session:
                raise _SessionLost(f"Sitzung bei '{self.name}' abgelaufen.") from exc
            hint = {401: " (Token falsch oder fehlt?)", 403: " (Zugriff verweigert)",
                    404: " (falsche URL?)"}.get(exc.code, "")
            raise McpError(f"Server '{self.name}': HTTP {exc.code}{hint}.") from exc
        except urllib.error.URLError as exc:
            reason = str(exc.reason)
            if "stimmt nicht mit dem Zertifikat" in reason:
                hint = " Das Zertifikat der App gehört zu einem anderen Schlüssel (App neu installiert oder Daten gelöscht?): neu holen."
            elif "CERTIFICATE_VERIFY_FAILED" in reason:
                hint = " Zertifikat nicht vertrauenswürdig: 'cert' in der Konfiguration angeben."
            else:
                hint = ""
            raise McpError(f"Server '{self.name}' nicht erreichbar: {reason}.{hint}") from exc
        except (OSError, ValueError) as exc:  # Zeitüberschreitung, Verbindungsabbruch
            raise McpError(f"Server '{self.name}' nicht erreichbar: {exc}") from exc

    def _read_reply(self, response, request_id: int, timeout: float) -> dict:
        deadline, total = time.monotonic() + timeout, 0
        if response.headers.get_content_type() == "text/event-stream":
            data: list[str] = []
            for raw in response:
                total += len(raw)
                if total > MAX_RESPONSE_BYTES or time.monotonic() > deadline:
                    raise McpError(f"Antwort von '{self.name}' zu groß oder zu langsam.")
                line = raw.decode("utf-8", errors="replace").rstrip("\r\n")
                if line.startswith("data:"):
                    data.append(line[5:][1:] if line[5:6] == " " else line[5:])
                elif not line and data:
                    found = self._match("\n".join(data), request_id)
                    data = []
                    if found is not None:
                        return found
            found = self._match("\n".join(data), request_id) if data else None
            if found is not None:
                return found
            raise McpError(f"Server '{self.name}' hat nicht geantwortet (Strom ohne Ergebnis).")
        body = response.read(MAX_RESPONSE_BYTES + 1)
        if len(body) > MAX_RESPONSE_BYTES:
            raise McpError(f"Antwort von '{self.name}' zu groß.")
        found = self._match(body.decode("utf-8", errors="replace"), request_id)
        if found is None:
            raise McpError(f"Server '{self.name}' hat keine gültige Antwort geliefert.")
        return found

    @staticmethod
    def _match(text: str, request_id: int) -> dict | None:
        """Findet die Antwort auf request_id (einzelne Nachricht oder Batch); Benachrichtigungen werden übergangen."""
        try:
            payload = json.loads(text)
        except ValueError:
            return None
        for message in payload if isinstance(payload, list) else [payload]:
            if isinstance(message, dict) and message.get("id") == request_id and ("result" in message or "error" in message):
                return message
        return None
