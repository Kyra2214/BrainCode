from __future__ import annotations
import re
from pathlib import Path

_SECRET = re.compile(r"(?i)(api[_-]?key|token|password|secret|bearer)\s*[:=]")
_INJECTION = re.compile(r"(?i)(ignore\s+(all|previous|prior)\s+instructions|system\s+message|developer\s+message)")

def validate_text(value: str, max_length: int = 100_000) -> str:
    if not isinstance(value, str) or not value.strip(): raise ValueError("text input cannot be empty")
    if len(value) > max_length: raise ValueError("text input exceeds limit")
    if "\x00" in value: raise ValueError("null byte is not allowed")
    if _SECRET.search(value): raise ValueError("secret-like input must be provided by reference")
    return value

def detect_prompt_injection(value: str) -> tuple[bool, str]:
    return (True, "prompt injection marker detected") if _INJECTION.search(value) else (False, "")

def safe_join(root: str | Path, relative: str) -> Path:
    if "\x00" in relative: raise ValueError("null byte in path")
    root_path, candidate = Path(root).resolve(), (Path(root) / relative).resolve()
    if candidate != root_path and root_path not in candidate.parents: raise PermissionError("path traversal rejected")
    return candidate
