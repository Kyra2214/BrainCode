from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Mapping, Protocol


@dataclass(frozen=True)
class ProviderRequest:
    model: str
    prompt: str
    credential_ref: str
    timeout_seconds: float = 30.0
    max_tokens: int | None = None
    stream: bool = False


@dataclass(frozen=True)
class ProviderResponse:
    provider: str
    model: str
    text: str
    usage: Mapping[str, int]
    status_code: int = 200
    complete: bool = True
    raw: Mapping[str, Any] | None = None


class HttpTransport(Protocol):
    def post(self, url: str, *, headers: Mapping[str, str], json: Mapping[str, Any], timeout: float) -> tuple[int, Mapping[str, Any]]: ...


class ProviderAdapter:
    provider = "generic"

    def __init__(self, transport: HttpTransport, base_url: str):
        self.transport, self.base_url = transport, base_url.rstrip("/")

    def _headers(self, request: ProviderRequest, credential: str) -> dict[str, str]:
        if not request.credential_ref or not credential:
            raise PermissionError("provider credential must be supplied by reference")
        return {"Authorization": f"Bearer {credential}", "Content-Type": "application/json"}

    def _request(self, request: ProviderRequest, credential: str, payload: Mapping[str, Any]) -> ProviderResponse:
        status, body = self.transport.post(self.base_url, headers=self._headers(request, credential), json=payload, timeout=request.timeout_seconds)
        if status == 429:
            raise RuntimeError("provider rate limit (429)")
        if status >= 400:
            raise RuntimeError(f"provider HTTP error: {status}")
        text = self.extract_text(body)
        if not text:
            raise RuntimeError("provider returned an incomplete response")
        usage = body.get("usage", {}) if isinstance(body, Mapping) else {}
        return ProviderResponse(self.provider, request.model, text, usage, status, True, body)

    def extract_text(self, body: Mapping[str, Any]) -> str:
        raise NotImplementedError


class OpenAICompatibleAdapter(ProviderAdapter):
    provider = "openai-compatible"
    def extract_text(self, body: Mapping[str, Any]) -> str:
        choices = body.get("choices", [])
        return str(choices[0].get("message", {}).get("content", "")) if choices else ""
    def complete(self, request: ProviderRequest, credential: str) -> ProviderResponse:
        return self._request(request, credential, {"model": request.model, "messages": [{"role": "user", "content": request.prompt}], "max_tokens": request.max_tokens, "stream": request.stream})


class GeminiAdapter(ProviderAdapter):
    provider = "gemini"
    def extract_text(self, body: Mapping[str, Any]) -> str:
        candidates = body.get("candidates", [])
        parts = candidates[0].get("content", {}).get("parts", []) if candidates else []
        return "".join(str(part.get("text", "")) for part in parts)
    def complete(self, request: ProviderRequest, credential: str) -> ProviderResponse:
        return self._request(request, credential, {"contents": [{"parts": [{"text": request.prompt}]}], "generationConfig": {"maxOutputTokens": request.max_tokens}})


class AnthropicAdapter(ProviderAdapter):
    provider = "anthropic"
    def _headers(self, request: ProviderRequest, credential: str) -> dict[str, str]:
        headers = super()._headers(request, credential); headers["x-api-key"] = credential; headers.pop("Authorization", None); headers["anthropic-version"] = "2023-06-01"; return headers
    def extract_text(self, body: Mapping[str, Any]) -> str:
        return "".join(str(item.get("text", "")) for item in body.get("content", []) if item.get("type") == "text")
    def complete(self, request: ProviderRequest, credential: str) -> ProviderResponse:
        return self._request(request, credential, {"model": request.model, "max_tokens": request.max_tokens or 1024, "messages": [{"role": "user", "content": request.prompt}]})


class OpenRouterAdapter(OpenAICompatibleAdapter):
    provider = "openrouter"


class LocalProviderAdapter(OpenAICompatibleAdapter):
    provider = "local"
    def _headers(self, request: ProviderRequest, credential: str) -> dict[str, str]:
        return {"Content-Type": "application/json"}
