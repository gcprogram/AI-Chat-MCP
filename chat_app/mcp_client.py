"""Minimaler MCP-Client über stdio (JSON-RPC, eine Nachricht pro Zeile) - ohne externe Abhängigkeiten.

Reicht für lokale MCP-Server (npx/uvx/python): initialize, tools/list, tools/call."""
from __future__ import annotations

import json
import logging
import os
import shutil
import subprocess
import sys
import threading
from itertools import count

log = logging.getLogger(__name__)
PROTOCOL_VERSION = "2024-11-05"  # Server handeln bei Bedarf eine ältere/neuere Version aus


class McpError(Exception):
    pass


class McpServer:
    def __init__(self, command: str, args: list[str] | tuple[str, ...] = (), env: dict[str, str] | None = None,
                 cwd: str | None = None, name: str = "mcp"):
        self.name = name
        self.command, self.args, self.env, self.cwd = command, list(args), env or {}, cwd
        self.proc: subprocess.Popen | None = None
        self._ids = count(1)
        self._pending: dict[int, tuple[threading.Event, dict]] = {}
        self._lock = threading.Lock()
        self._write_lock = threading.Lock()

    # --- Lebenszyklus --------------------------------------------------------------------------

    def start(self, timeout: float = 30.0) -> None:
        command = sys.executable if self.command in ("python", "python3") else (shutil.which(self.command) or self.command)
        try:
            self.proc = subprocess.Popen(
                [command, *self.args], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                env={**os.environ, **self.env}, cwd=self.cwd,
                creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
        except OSError as exc:
            raise McpError(f"Server '{self.name}' nicht startbar ({self.command}): {exc}") from exc
        threading.Thread(target=self._read_stdout, daemon=True).start()
        threading.Thread(target=self._drain_stderr, daemon=True).start()
        try:
            self.request("initialize", {"protocolVersion": PROTOCOL_VERSION, "capabilities": {},
                                        "clientInfo": {"name": "ai-chat-mcp", "version": "1.0"}}, timeout)
            self._send({"jsonrpc": "2.0", "method": "notifications/initialized"})
        except Exception:
            self.close()
            raise

    def close(self) -> None:
        proc, self.proc = self.proc, None
        if proc is None:
            return
        try:
            self._terminate(proc)
        finally:
            for pipe in (proc.stdin, proc.stdout, proc.stderr):
                try:
                    pipe.close()  # type: ignore[union-attr]
                except (OSError, AttributeError):
                    pass

    @staticmethod
    def _terminate(proc: subprocess.Popen) -> None:
        if proc.poll() is not None:
            return
        try:
            if os.name == "nt":  # npx & Co. starten Kindprozesse - den ganzen Baum beenden
                subprocess.run(["taskkill", "/F", "/T", "/PID", str(proc.pid)], capture_output=True, timeout=10,
                               creationflags=subprocess.CREATE_NO_WINDOW)
            else:
                proc.terminate()
            proc.wait(timeout=5)
        except (OSError, subprocess.SubprocessError):
            proc.kill()

    # --- Protokoll -----------------------------------------------------------------------------

    def _send(self, message: dict) -> None:
        if self.proc is None or self.proc.poll() is not None or self.proc.stdin is None:
            raise McpError(f"Server '{self.name}' läuft nicht mehr.")
        data = (json.dumps(message, ensure_ascii=False) + "\n").encode("utf-8")
        with self._write_lock:
            try:
                self.proc.stdin.write(data)
                self.proc.stdin.flush()
            except OSError as exc:
                raise McpError(f"Server '{self.name}' nicht erreichbar: {exc}") from exc

    def request(self, method: str, params: dict | None = None, timeout: float = 30.0) -> dict:
        request_id = next(self._ids)
        event, slot = threading.Event(), {}
        with self._lock:
            self._pending[request_id] = (event, slot)
        try:
            self._send({"jsonrpc": "2.0", "id": request_id, "method": method, "params": params or {}})
            if not event.wait(timeout):
                raise McpError(f"Zeitüberschreitung bei '{method}' (Server '{self.name}', {timeout:.0f} s).")
        finally:
            with self._lock:
                self._pending.pop(request_id, None)
        if "error" in slot:
            error = slot["error"]
            raise McpError(error.get("message", str(error)) if isinstance(error, dict) else str(error))
        return slot.get("result") or {}

    def _read_stdout(self) -> None:
        proc = self.proc
        try:
            for raw in proc.stdout:  # type: ignore[union-attr]
                try:
                    message = json.loads(raw.decode("utf-8", errors="replace"))
                except ValueError:
                    continue  # Server schreibt Logzeilen auf stdout: ignorieren
                if not isinstance(message, dict):
                    continue
                if "method" in message:
                    self._answer_server_request(message)
                    continue
                with self._lock:
                    entry = self._pending.get(message.get("id"))
                if entry:
                    entry[1].update({k: message[k] for k in ("result", "error") if k in message})
                    entry[0].set()
        finally:  # Prozess beendet: alle Wartenden aufwecken
            with self._lock:
                waiting = list(self._pending.values())
            for event, slot in waiting:
                slot.setdefault("error", {"message": f"Server '{self.name}' wurde beendet."})
                event.set()

    def _answer_server_request(self, message: dict) -> None:
        if "id" not in message:  # Benachrichtigung
            return
        if message["method"] == "ping":
            reply = {"jsonrpc": "2.0", "id": message["id"], "result": {}}
        else:
            reply = {"jsonrpc": "2.0", "id": message["id"],
                     "error": {"code": -32601, "message": "Nicht unterstützt"}}
        try:
            self._send(reply)
        except McpError:
            pass

    def _drain_stderr(self) -> None:
        for raw in self.proc.stderr:  # type: ignore[union-attr]
            log.debug("[%s] %s", self.name, raw.decode("utf-8", errors="replace").rstrip())

    # --- Werkzeuge -----------------------------------------------------------------------------

    def list_tools(self, timeout: float = 30.0) -> list[dict]:
        tools: list[dict] = []
        cursor = None
        for _ in range(20):  # Seitenlimit gegen Endlosschleifen
            result = self.request("tools/list", {"cursor": cursor} if cursor else {}, timeout)
            tools += [t for t in result.get("tools", []) if isinstance(t, dict) and t.get("name")]
            cursor = result.get("nextCursor")
            if not cursor:
                break
        return tools

    def call_tool(self, name: str, arguments: dict, timeout: float = 60.0) -> tuple[str, bool]:
        """Liefert (Text, ist_Fehler)."""
        result = self.request("tools/call", {"name": name, "arguments": arguments}, timeout)
        parts = []
        for item in result.get("content") or []:
            if not isinstance(item, dict):
                continue
            kind = item.get("type")
            if kind == "text":
                parts.append(str(item.get("text", "")))
            elif kind:
                parts.append(f"[{kind}-Inhalt, nicht darstellbar]")
        if not parts and result.get("structuredContent") is not None:
            parts.append(json.dumps(result["structuredContent"], ensure_ascii=False))
        return "\n".join(parts).strip(), bool(result.get("isError"))
