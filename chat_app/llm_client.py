"""Streaming-Client für OpenAI-kompatible Chat-Endpunkte (LM Studio, OpenAI, OpenRouter, ...)."""
from __future__ import annotations

import json
import re
import threading
from dataclasses import dataclass
from typing import Iterator

import requests


class LLMError(Exception):
    pass


@dataclass(frozen=True)
class StreamEvent:
    kind: str  # "content" | "reasoning" | "usage" | "tool_call" (text = Funktionsname)
    text: str = ""
    usage: dict | None = None
    arguments: str = ""  # nur bei tool_call: Argumente als JSON-Text


def dialect_for(base_url: str) -> str:
    """Welcher Anbieter hinter der Adresse steckt: "openai", "anthropic", "gemini" oder "standard" (LM Studio, Ollama, eigene Server).
    OpenAI und Gemini lehnen Felder wie repeat_penalty mit HTTP 400 ab, neuere OpenAI-Modelle wollen max_completion_tokens."""
    url = base_url.lower()
    if "api.openai.com" in url:
        return "openai"
    if "api.anthropic.com" in url:
        return "anthropic"
    if "generativelanguage.googleapis.com" in url:
        return "gemini"
    return "standard"


def is_openai_reasoning(model: str) -> bool:
    """OpenAI-Denkmodelle (o1, o3, o4-mini, gpt-5 ...): nur die Standardtemperatur, kein stop, keine Sampling-Parameter."""
    m = model.lower().removeprefix("ft:")
    return bool(re.match(r"^(o\d|gpt-5)", m)) and not m.startswith("gpt-5-chat")


def build_payload(dialect: str, model: str, messages: list[dict], temperature: float, max_tokens: int,
                  stop: list[str] | None, extra: dict | None) -> dict:
    """Die Chat-Anfrage, auf das beschränkt, was der Anbieter annimmt (gleiche Regeln wie ChatPayload in der Android-App)."""
    reasoning = dialect == "openai" and is_openai_reasoning(model)
    payload: dict = {"model": model, "messages": messages, "stream": True}
    if not reasoning:
        payload["temperature"] = temperature
    payload["max_completion_tokens" if dialect == "openai" else "max_tokens"] = max_tokens
    if dialect in ("standard", "openai"):
        payload["stream_options"] = {"include_usage": True}
    allowed = {
        "standard": None,
        "openai": set() if reasoning else {"top_p", "frequency_penalty", "presence_penalty"},
        "anthropic": {"top_p"},
        "gemini": {"top_p", "frequency_penalty", "presence_penalty"},
    }[dialect]
    for key, value in (extra or {}).items():
        if allowed is None or key in allowed:
            payload[key] = value
    if stop and not reasoning:
        payload["stop"] = stop
    return payload


class ChatClient:
    def __init__(self, base_url: str, api_key: str = ""):
        self.base_url = base_url.rstrip("/")
        self.api_key = api_key
        self.dialect = dialect_for(self.base_url)

    def _headers(self) -> dict[str, str]:
        headers = {"Content-Type": "application/json"}
        if self.api_key:
            headers["Authorization"] = f"Bearer {self.api_key}"
            if self.dialect == "anthropic":
                headers["x-api-key"] = self.api_key
                headers["anthropic-version"] = "2023-06-01"
        return headers

    def list_models(self) -> list[str]:
        url = f"{self.base_url}/models"
        try:
            response = requests.get(url, headers=self._headers(), timeout=10)
        except requests.RequestException as exc:
            raise LLMError(f"Server nicht erreichbar ({url}): {exc}") from exc
        if response.status_code == 401:
            raise LLMError("Nicht autorisiert - API-Schlüssel prüfen.")
        if response.status_code != 200:
            raise LLMError(f"Serverfehler {response.status_code}: {response.text[:300]}")
        try:
            ids = [m["id"] for m in response.json()["data"]]
        except (ValueError, KeyError, TypeError) as exc:
            raise LLMError(f"Unerwartetes Antwortformat: {response.text[:200]}") from exc
        return [i for i in ids if "embed" not in i.lower()]

    def stream_chat(self, model: str, messages: list[dict], *, temperature: float, max_tokens: int,
                    stop: list[str] | None = None, cancel: threading.Event | None = None,
                    extra: dict | None = None, tools: list[dict] | None = None) -> Iterator[StreamEvent]:
        """tools: Funktionsbeschreibungen (OpenAI-Format) für natives Tool-Calling; Aufrufe kommen am Ende des
        Streams als StreamEvent("tool_call") - die Deltas werden hier zusammengesetzt."""
        payload = build_payload(self.dialect, model, messages, temperature, max_tokens, stop, extra)
        if tools:
            payload["tools"] = tools
        calls: dict[int, dict] = {}
        url = f"{self.base_url}/chat/completions"
        try:
            # Lese-Timeout großzügig: LM Studio lädt das Modell beim ersten Request nach.
            response = requests.post(url, headers=self._headers(), json=payload, stream=True, timeout=(10, 300))
        except requests.RequestException as exc:
            raise LLMError(f"Server nicht erreichbar ({url}): {exc}") from exc
        with response:
            if response.status_code == 401:
                raise LLMError("Nicht autorisiert - API-Schlüssel prüfen.")
            if response.status_code != 200:
                raise LLMError(f"Serverfehler {response.status_code}: {response.text[:300]}")
            try:
                for raw in response.iter_lines():  # Bytes: SSE hat kein Charset, Umlaute selbst als UTF-8 lesen
                    if cancel is not None and cancel.is_set():
                        return
                    line = raw.decode("utf-8", errors="replace").strip()
                    if not line.startswith("data:"):
                        continue
                    data = line[5:].strip()
                    if data == "[DONE]":
                        break
                    yield from self._events(data, calls)
            except requests.RequestException as exc:
                raise LLMError(f"Verbindung während der Antwort abgebrochen: {exc}") from exc
            for _, call in sorted(calls.items()):
                if call["name"]:
                    yield StreamEvent("tool_call", call["name"], arguments=call["arguments"])

    @staticmethod
    def _events(data: str, calls: dict[int, dict] | None = None) -> Iterator[StreamEvent]:
        try:
            chunk = json.loads(data)
        except ValueError:
            return
        if chunk.get("error"):
            raise LLMError(f"Serverfehler: {chunk['error']}")
        for choice in chunk.get("choices") or []:
            delta = choice.get("delta") or {}
            reasoning = delta.get("reasoning_content") or delta.get("reasoning")
            if reasoning:
                yield StreamEvent("reasoning", reasoning)
            if delta.get("content"):
                yield StreamEvent("content", delta["content"])
            for part in delta.get("tool_calls") or []:  # Name kommt im ersten Fragment, Argumente stückweise
                if calls is None or not isinstance(part, dict):
                    continue
                call = calls.setdefault(int(part.get("index") or 0), {"name": "", "arguments": ""})
                function = part.get("function") or {}
                name = function.get("name") or ""
                if name != call["name"]:  # manche Server wiederholen den vollständigen Namen in jedem Fragment
                    call["name"] += name
                call["arguments"] += function.get("arguments") or ""
        if chunk.get("usage"):
            yield StreamEvent("usage", usage=chunk["usage"])
