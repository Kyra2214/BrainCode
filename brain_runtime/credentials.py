from __future__ import annotations

import inspect
import os
import re
from dataclasses import dataclass
from typing import Any, Callable, Mapping


_REF = re.compile(r"^[A-Za-z_][A-Za-z0-9_]{0,127}$")
_SENSITIVE_KEYS = frozenset({
    "secret", "secrets", "token", "tokens", "password", "passwd", "api_key", "apikey",
    "credential", "credentials", "authorization", "access_token", "refresh_token", "private_key",
    "client_secret", "secret_ref", "credential_ref",
})
_SECRET_TEXT = re.compile(r"(?i)(bearer\s+)[A-Za-z0-9._~+/=-]+|((?:api[_-]?key|access[_-]?token|refresh[_-]?token|client[_-]?secret|password)\s*[:=]\s*)[^\s,;]+")


@dataclass(frozen=True)
class CredentialRef:
    name: str
    provider: str = ""
    scope: str = "runtime"

    def __post_init__(self) -> None:
        if not _REF.fullmatch(self.name):
            raise ValueError("invalid credential reference")
        if any(not isinstance(value, str) or any(ord(char) < 32 for char in value) for value in (self.provider, self.scope)):
            raise ValueError("invalid credential metadata")


class CredentialVault:
    """Resolve secrets only at execution time; refs and sanitized views stay safe."""

    def __init__(self, values: Mapping[str, str] | None = None):
        self._values: dict[str, str] = {}
        self._revoked: set[str] = set()
        for name, value in (values or {}).items():
            if not _REF.fullmatch(str(name)) or not isinstance(value, str) or not value:
                raise ValueError("invalid credential value")
            self._values[str(name)] = value

    def register(self, ref: CredentialRef | str, value: str, *, replace: bool = False) -> CredentialRef:
        reference = ref if isinstance(ref, CredentialRef) else CredentialRef(ref)
        if not isinstance(value, str) or not value:
            raise ValueError("credential value must be a non-empty string")
        if reference.name in self._values and not replace and reference.name not in self._revoked:
            raise ValueError(f"credential already registered: {reference.name}")
        self._values[reference.name] = value
        self._revoked.discard(reference.name)
        return reference

    def revoke(self, ref: CredentialRef | str) -> None:
        reference = ref if isinstance(ref, CredentialRef) else CredentialRef(ref)
        self._revoked.add(reference.name)
        # Drop the lookup value immediately; a revoked ref must not fall back
        # to the process environment.
        self._values.pop(reference.name, None)

    def is_revoked(self, ref: CredentialRef | str) -> bool:
        reference = ref if isinstance(ref, CredentialRef) else CredentialRef(ref)
        return reference.name in self._revoked

    def resolve(self, ref: CredentialRef) -> str:
        if not isinstance(ref, CredentialRef):
            raise TypeError("resolve expects CredentialRef")
        if ref.name in self._revoked:
            raise PermissionError(f"credential revoked: {ref.name}")
        value = self._values.get(ref.name, os.environ.get(ref.name, ""))
        if not value:
            raise PermissionError(f"credential unavailable: {ref.name}")
        return value

    def resolve_reference(self, reference: str | CredentialRef) -> str:
        return self.resolve(reference if isinstance(reference, CredentialRef) else CredentialRef(reference))

    @staticmethod
    def _sanitize(value: Any, known_values: tuple[str, ...] = ()) -> Any:
        if isinstance(value, dict):
            result = {}
            for key, item in value.items():
                key_text = str(key).lower().replace("-", "_")
                result[key] = "[CREDENTIAL_REF]" if key_text in _SENSITIVE_KEYS or any(marker in key_text for marker in ("secret", "token", "password", "api_key", "credential")) else CredentialVault._sanitize(item, known_values)
            return result
        if isinstance(value, list):
            return [CredentialVault._sanitize(item, known_values) for item in value]
        if isinstance(value, tuple):
            return tuple(CredentialVault._sanitize(item, known_values) for item in value)
        if isinstance(value, set):
            return {CredentialVault._sanitize(item, known_values) for item in value}
        if isinstance(value, str):
            redacted = value
            for secret in known_values:
                if secret:
                    redacted = redacted.replace(secret, "[REDACTED]")
            return _SECRET_TEXT.sub(lambda match: (match.group(1) or match.group(2) or "") + "[REDACTED]", redacted)
        return value

    @staticmethod
    def sanitize(payload: Any) -> Any:
        """Redact sensitive fields and common inline secret forms."""
        return CredentialVault._sanitize(payload)

    def sanitized(self, payload: Any) -> Any:
        """Redact payload fields plus every secret currently held by this vault."""
        return self._sanitize(payload, tuple(self._values.values()))

    def dispatch(self, dispatcher: Callable, request: Any, credential_ref: CredentialRef | None = None) -> Any:
        """Inject a credential only into the call; never mutate request/event data."""
        if credential_ref is None:
            return dispatcher(request)
        secret = self.resolve(credential_ref)
        try:
            signature = inspect.signature(dispatcher)
            signature.bind(request, credential=secret)
        except (TypeError, ValueError):
            # Preserve the legacy one-argument dispatcher interface. A value
            # error means the callable has no inspectable signature, so keep
            # the historical two-argument attempt as a compatibility fallback.
            try:
                return dispatcher(request, credential=secret)
            except TypeError:
                return dispatcher(request)
        return dispatcher(request, credential=secret)
