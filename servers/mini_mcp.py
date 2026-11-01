"""Gemeinsamer Unterbau für die kleinen MCP-Server in diesem Ordner (stdio, JSON-RPC pro Zeile, nur Standardbibliothek)."""
import json
import sys
import urllib.request

USER_AGENT = "Mozilla/5.0 (compatible; ai-chat-mcp)"


class ToolError(Exception):
    """Fehler, der als Werkzeugergebnis (isError) an das Modell geht."""


def tool(name, description, properties=None, required=()):
    """Beschreibung eines Werkzeugs; properties: {Name: (Typ, Beschreibung)}."""
    schema = {"type": "object", "properties": {k: {"type": t, "description": d} for k, (t, d) in (properties or {}).items()}}
    if required:
        schema["required"] = list(required)
    return {"name": name, "description": description, "inputSchema": schema, "annotations": {"readOnlyHint": True}}


def fetch(url, data=None, timeout=15):
    """HTTP-GET (oder POST mit data) und Text zurück; Netzfehler werden zu ToolError."""
    request = urllib.request.Request(url, data, {"User-Agent": USER_AGENT})
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return response.read().decode("utf-8", errors="replace")
    except OSError as exc:
        raise ToolError(f"Keine Verbindung zum Dienst ({exc}).") from exc


def fetch_json(url, timeout=15):
    try:
        return json.loads(fetch(url, timeout=timeout))
    except ValueError as exc:
        raise ToolError("Der Dienst hat keine gültige Antwort geliefert.") from exc


def serve(server_name, tools, handlers):
    """Hauptschleife: initialize, tools/list, tools/call. handlers: {Werkzeugname: Funktion(arguments) -> Text}."""
    def respond(message):
        method, params = message.get("method"), message.get("params") or {}
        if method == "initialize":
            return {"protocolVersion": params.get("protocolVersion", "2024-11-05"), "capabilities": {"tools": {}},
                    "serverInfo": {"name": server_name, "version": "1.0"}}
        if method == "tools/list":
            return {"tools": tools}
        if method == "tools/call":
            handler = handlers.get(params.get("name"))
            if handler is None:
                return {"content": [{"type": "text", "text": f"Unbekanntes Werkzeug: {params.get('name')}"}], "isError": True}
            try:
                return {"content": [{"type": "text", "text": handler(params.get("arguments") or {})}], "isError": False}
            except ToolError as exc:
                return {"content": [{"type": "text", "text": str(exc)}], "isError": True}
        if method == "ping":
            return {}
        raise KeyError(method)

    for raw in sys.stdin.buffer:
        try:
            message = json.loads(raw.decode("utf-8"))
        except ValueError:
            continue
        if not isinstance(message, dict) or "id" not in message:  # Benachrichtigung
            continue
        try:
            reply = {"jsonrpc": "2.0", "id": message["id"], "result": respond(message)}
        except KeyError:
            reply = {"jsonrpc": "2.0", "id": message["id"], "error": {"code": -32601, "message": "Methode unbekannt"}}
        sys.stdout.buffer.write((json.dumps(reply, ensure_ascii=False) + "\n").encode("utf-8"))
        sys.stdout.buffer.flush()
