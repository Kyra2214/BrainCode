from __future__ import annotations
import re
from typing import Any, Iterable
from .events import redact

_SECRET = re.compile(r"(?i)(api[_-]?key|token|password|secret|bearer|authorization)\s*[:=]")
_INJECTION = re.compile(r"(?i)(ignore\s+(?:all\s+)?(?:previous|prior)|system\s+message|developer\s+(?:message|instructions)|reveal\s+(?:the\s+)?prompt|jailbreak)")

def flatten_text(value: Any) -> Iterable[str]:
    if isinstance(value, dict):
        for key, item in value.items(): yield str(key); yield from flatten_text(item)
    elif isinstance(value, (list, tuple, set)):
        for item in value: yield from flatten_text(item)
    elif isinstance(value, str): yield value

def find_secret_leaks(value: Any) -> tuple[str, ...]:
    leaks = []
    if isinstance(value, dict):
        for key, item in value.items():
            if str(key).lower() in {"secret", "token", "password", "api_key", "apikey", "authorization"} and item not in (None, "", "[REDACTED]", "[CREDENTIAL_REF]"):
                leaks.append(str(key))
            leaks.extend(find_secret_leaks(item))
        return tuple(leaks)
    return tuple(text for text in flatten_text(value) if _SECRET.search(text))
def find_prompt_injection(value: Any) -> tuple[str, ...]:
    return tuple(text for text in flatten_text(value) if _INJECTION.search(text))
def sanitize_untrusted(value: Any) -> Any:
    leaks = find_secret_leaks(value)
    if leaks: raise ValueError("secret-like content cannot cross trust boundary")
    return redact(value)
def assert_no_injection(value: Any) -> None:
    if find_prompt_injection(value): raise ValueError("prompt-injection-like content cannot cross trust boundary")
