"""Streaming-Client für OpenAI-kompatible Chat-Endpunkte (LM Studio, OpenAI, OpenRouter, ...)."""
from __future__ import annotations

import json
import threading
from dataclasses import dataclass
from typing import Iterator

import requests


class LLMError(Exception):
    pass


@dataclass(frozen=True)
class StreamEvent:
    kind: str  # "content" | "reasoning" | "usage"
    text: str = ""
    usage: dict | None = None


class ChatClient:
    def __init__(self, base_url: str, api_key: str = ""):
        self.base_url = base_url.rstrip("/")
        self.api_key = api_key

    def _headers(self) -> dict[str, str]:
        headers = {"Content-Type": "application/json"}
        if self.api_key:
            headers["Authorization"] = f"Bearer {self.api_key}"
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
                    extra: dict | None = None) -> Iterator[StreamEvent]:
        payload = {
            "model": model, "messages": messages, "stream": True,
            "temperature": temperature, "max_tokens": max_tokens,
            "stream_options": {"include_usage": True},
        }
        payload.update(extra or {})  # z. B. top_p, frequency_penalty, repeat_penalty
        if stop:
            payload["stop"] = stop
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
                        return
                    yield from self._events(data)
            except requests.RequestException as exc:
                raise LLMError(f"Verbindung während der Antwort abgebrochen: {exc}") from exc

    @staticmethod
    def _events(data: str) -> Iterator[StreamEvent]:
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
        if chunk.get("usage"):
            yield StreamEvent("usage", usage=chunk["usage"])
