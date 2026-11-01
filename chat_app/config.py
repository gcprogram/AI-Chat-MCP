"""Konfiguration. Defekte oder unvollständige config.json führt nie zum Absturz, sondern zu Defaults."""
from __future__ import annotations

import json
import logging
from dataclasses import asdict, dataclass, field, fields
from pathlib import Path

log = logging.getLogger(__name__)

APP_DIR = Path(__file__).resolve().parent.parent
CONFIG_PATH = APP_DIR / "config.json"


@dataclass
class Config:
    # Voller Basis-URL inkl. /v1 - dadurch passt auch jeder OpenAI-kompatible Online-Dienst.
    base_url: str = "http://127.0.0.1:1234/v1"
    api_key: str = ""
    model: str = ""
    models: list[str] = field(default_factory=list)
    persona: str = "companion"
    user_name: str = "Nutzer"
    temperature: float = 0.7
    max_reply_tokens: int = 600
    context_tokens: int = 8192

    def sanitize(self) -> "Config":
        self.base_url = str(self.base_url).strip().rstrip("/") or "http://127.0.0.1:1234/v1"
        self.user_name = str(self.user_name).strip() or "Nutzer"
        if not isinstance(self.models, list):
            self.models = []
        for name, default, lo, hi in (("temperature", 0.7, 0.0, 2.0),
                                      ("max_reply_tokens", 600, 16, 8192),
                                      ("context_tokens", 8192, 1024, 1_000_000)):
            try:
                value = type(default)(getattr(self, name))
            except (TypeError, ValueError):
                value = default
            setattr(self, name, max(lo, min(hi, value)))
        return self

    @classmethod
    def load(cls, path: Path | str = CONFIG_PATH) -> "Config":
        path = Path(path)
        if not path.exists():
            return cls()
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
            if not isinstance(data, dict):
                raise ValueError("Wurzelelement ist kein Objekt")
        except (OSError, ValueError) as exc:
            log.error("Konfiguration %s nicht lesbar (%s) - verwende Defaults.", path, exc)
            return cls()
        if "base_url" not in data and data.get("ip"):  # alte Konfiguration (ip + port)
            data["base_url"] = f"http://{data['ip']}:{data.get('port') or 1234}/v1"
        known = {f.name for f in fields(cls)}
        return cls(**{k: v for k, v in data.items() if k in known}).sanitize()

    def save(self, path: Path | str = CONFIG_PATH) -> None:
        self.sanitize()
        Path(path).write_text(json.dumps(asdict(self), indent=4, ensure_ascii=False), encoding="utf-8")
