"""Liest die Werkzeuge (und bei einer Fernbedienung die Tasten) eines laufenden MCP-Servers und macht daraus Aliase
für mcp_servers.json: dann muss niemand Codes oder Namen von Hand abschreiben.

Aufruf (Server muss in mcp_servers.json stehen und erreichbar sein):
    set PYTHONPATH=. && python -m chat_app.server_import remote --prefix tv- [--dry-run]
"""
from __future__ import annotations

import argparse
import json
import re
import shutil
import sys
from pathlib import Path
from typing import Any

from .config import MCP_CONFIG_PATH
from .tools import ToolRegistry, normalize_name


def slug(text: str) -> str:
    return re.sub(r"[^a-z0-9]+", "-", text.strip().lower()).strip("-") or "x"


def _first_sentence(text: str, limit: int = 120) -> str:
    text = " ".join(str(text or "").split())
    match = re.search(r"[.!?](?:\s|$)", text)
    if match:
        text = text[:match.end()].strip()
    return text if len(text) <= limit else text[:limit - 1].rstrip() + "…"


def alias_entries(server: str, tools: list[dict], buttons: list[dict] | None, prefix: str) -> dict[str, dict[str, Any]]:
    """Aliaseinträge für alle Werkzeuge des Servers. Mit Tastenliste (Werkzeuge "buttons" und "send"): ein kurzer Eintrag je Taste
    (nur server + button + hint), "send"/"buttons" selbst entfallen. Sonst ein Eintrag je Werkzeug."""
    names = {t.get("name") for t in tools}
    out: dict[str, dict[str, Any]] = {}
    remote = buttons is not None and {"buttons", "send"} <= names
    if remote:
        for item in buttons or []:
            if not isinstance(item, dict) or not isinstance(item.get("code"), int):
                continue
            label = str(item.get("label") or "").strip()
            shown = label or f"0x{item['code']:02x}"
            out[normalize_name(prefix + slug(shown))] = {"server": server, "button": shown, "hint": f"Press {shown}"}
    for tool in tools:
        name = str(tool.get("name") or "")
        if not name or (remote and name in ("send", "buttons")):
            continue
        entry: dict[str, Any] = {"server": server, "tool": name, "hint": _first_sentence(tool.get("description", "")) or name}
        if remote and name == "off":
            entry.update(result="discard", status="Off")
        out[normalize_name(prefix + slug(name))] = entry
    return out


def merge(data: dict, server: str, tools: list[dict], buttons: list[dict] | None, prefix: str) -> tuple[list[str], list[str]]:
    """Fügt die neuen Aliase in die Konfiguration ein (vorhandene bleiben unangetastet). Liefert (neu, übersprungen).
    Setzt "raw": false beim Server, damit nur die Aliase sichtbar sind."""
    aliases = data.setdefault("aliases", {})
    added, skipped = [], []
    for name, entry in alias_entries(server, tools, buttons, prefix).items():
        (skipped if name in aliases else added).append(name)
        aliases.setdefault(name, entry)
    entry = (data.get("servers") or data.get("mcpServers") or {}).get(server)
    if isinstance(entry, dict) and added:
        entry.setdefault("raw", False)
    return added, skipped


def read_server(registry: ToolRegistry, server: str) -> tuple[list[dict], list[dict] | None]:
    """Werkzeugliste des gestarteten Servers und, falls es "buttons" gibt, die Tasten."""
    client = registry.servers[server]
    tools = client.list_tools()
    buttons = None
    if any(t.get("name") == "buttons" for t in tools):
        text, is_error = client.call_tool("buttons", {}, 15.0)
        try:
            buttons = json.loads(text) if not is_error else None
        except ValueError:
            buttons = None
        if not isinstance(buttons, list):
            buttons = None
    return tools, buttons


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("server", help="Name des Servers in mcp_servers.json")
    parser.add_argument("--prefix", help="Vorsilbe der Aliasnamen (Standard: <server>-)")
    parser.add_argument("--config", default=str(MCP_CONFIG_PATH), help="Pfad der mcp_servers.json")
    parser.add_argument("--dry-run", action="store_true", help="nur anzeigen, nichts schreiben")
    args = parser.parse_args(argv)

    path = Path(args.config)
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as exc:
        print(f"{path} nicht lesbar: {exc}")
        return 1
    entry = (data.get("servers") or data.get("mcpServers") or {}).get(args.server)
    if not isinstance(entry, dict):
        print(f"Server '{args.server}' steht nicht in {path.name}.")
        return 1
    registry = ToolRegistry()
    try:
        server = registry._server_from_entry(args.server, entry, path.parent)
        registry.add_server(args.server, server, entry.get("tools"), expose_raw=False)
        tools, buttons = read_server(registry, args.server)
    except Exception as exc:  # noqa: BLE001 - jede Störung wird als Meldung gezeigt
        print(f"Server '{args.server}' nicht erreichbar: {exc}")
        return 1
    finally:
        registry.shutdown()
    added, skipped = merge(data, args.server, tools, buttons, args.prefix if args.prefix is not None else f"{args.server}-")
    print(f"{len(added)} neue Aliase: {', '.join(added) or '-'}")
    if skipped:
        print(f"{len(skipped)} schon vorhanden, nicht geändert: {', '.join(skipped)}")
    if args.dry_run or not added:
        return 0
    shutil.copyfile(path, path.with_suffix(path.suffix + ".bak"))
    path.write_text(json.dumps(data, indent=4, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"Geschrieben: {path} (Sicherung: {path.name}.bak)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
