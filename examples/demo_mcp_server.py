"""Winziger MCP-Server (stdio, nur Standardbibliothek) zum Ausprobieren und für die Tests.

Werkzeuge (englisch beschrieben): get_time (Datum/Uhrzeit), calculate (Grundrechenarten)."""
import ast
import json
import operator
import sys
from datetime import datetime

OPS = {ast.Add: operator.add, ast.Sub: operator.sub, ast.Mult: operator.mul, ast.Div: operator.truediv,
       ast.Pow: operator.pow, ast.Mod: operator.mod, ast.USub: operator.neg}

TOOLS = [
    {"name": "get_time", "description": "Returns the current date and time.",
     "inputSchema": {"type": "object", "properties": {}}, "annotations": {"readOnlyHint": True}},
    {"name": "calculate", "description": "Calculates an expression with + - * / ** % and parentheses.",
     "inputSchema": {"type": "object", "properties": {"expression": {"type": "string"}}, "required": ["expression"]},
     "annotations": {"readOnlyHint": True}},
]


def evaluate(node):
    if isinstance(node, ast.Expression):
        return evaluate(node.body)
    if isinstance(node, ast.Constant) and isinstance(node.value, (int, float)):
        return node.value
    if isinstance(node, ast.BinOp) and type(node.op) in OPS:
        left, right = evaluate(node.left), evaluate(node.right)
        if isinstance(node.op, ast.Pow) and abs(right) > 100:
            raise ValueError("Exponent too large")
        return OPS[type(node.op)](left, right)
    if isinstance(node, ast.UnaryOp) and type(node.op) in OPS:
        return OPS[type(node.op)](evaluate(node.operand))
    raise ValueError("Only numbers and + - * / ** % are allowed")


def call(name, arguments):
    if name == "get_time":
        return datetime.now().strftime("%A, %Y-%m-%d, %H:%M"), False
    if name == "calculate":
        try:
            return str(evaluate(ast.parse(str(arguments.get("expression", "")).replace(",", "."), mode="eval"))), False
        except (ValueError, SyntaxError, ZeroDivisionError) as exc:
            return f"Calculation error: {exc}", True
    return f"Unknown tool: {name}", True


def respond(message):
    method, params = message.get("method"), message.get("params") or {}
    if method == "initialize":
        return {"protocolVersion": params.get("protocolVersion", "2024-11-05"), "capabilities": {"tools": {}},
                "serverInfo": {"name": "demo", "version": "1.0"}}
    if method == "tools/list":
        return {"tools": TOOLS}
    if method == "tools/call":
        text, is_error = call(params.get("name"), params.get("arguments") or {})
        return {"content": [{"type": "text", "text": text}], "isError": is_error}
    if method == "ping":
        return {}
    raise KeyError(method)


def main():
    stdin = sys.stdin.buffer
    for raw in stdin:
        try:
            message = json.loads(raw.decode("utf-8"))
        except ValueError:
            continue
        if "id" not in message:  # Benachrichtigung (z. B. notifications/initialized)
            continue
        try:
            reply = {"jsonrpc": "2.0", "id": message["id"], "result": respond(message)}
        except KeyError:
            reply = {"jsonrpc": "2.0", "id": message["id"], "error": {"code": -32601, "message": "Methode unbekannt"}}
        sys.stdout.buffer.write((json.dumps(reply, ensure_ascii=False) + "\n").encode("utf-8"))
        sys.stdout.buffer.flush()


if __name__ == "__main__":
    main()
