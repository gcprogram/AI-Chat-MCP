"""MCP-Client über HTTP (Streamable HTTP): JSON-RPC per POST, die Antwort kommt als JSON oder als SSE-Strom.

Nur Standardbibliothek. Gedacht für MCP-Server im Heimnetz oder im Internet, z. B. die Fernbedienungs-App auf dem
Handy (ohne stdio-Bridge). Schutz der Zugangsdaten:
- Header-Werte dürfen `${UMGEBUNGSVARIABLE}` enthalten (der Token steht so nie in der Konfigurationsdatei);
- `http://` ist nur für lokale Adressen erlaubt (localhost, Heimnetz, `.local`), sonst `https://`;
- ein selbst signiertes Zertifikat wird über `cert` als einziger Vertrauensanker angegeben (nichts landet im
  Zertifikatsspeicher des Systems);
- Weiterleitungen werden nicht verfolgt (sonst ginge der Token an einen fremden Host)."""
from __future__ import annotations

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
            path = os.path.expanduser(self.cert)
            try:
                handlers.append(urllib.request.HTTPSHandler(context=ssl.create_default_context(cafile=path)))
            except (OSError, ssl.SSLError) as exc:
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
            hint = " Zertifikat nicht vertrauenswürdig: 'cert' in der Konfiguration angeben." \
                if "CERTIFICATE_VERIFY_FAILED" in reason else ""
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
