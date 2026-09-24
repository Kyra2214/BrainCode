from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timezone
from hashlib import sha256
import json
import re
from typing import Callable, Iterable

from .apis import ApiCatalogEntry, DynamicApiCatalog
from .signatures import SignatureVerifier


_LICENSES = frozenset({
    "MIT", "Apache-2.0", "BSD-2-Clause", "BSD-3-Clause", "ISC", "MPL-2.0",
    "LGPL-2.1-only", "LGPL-2.1-or-later", "LGPL-3.0-only", "LGPL-3.0-or-later",
    "GPL-2.0-only", "GPL-2.0-or-later", "GPL-3.0-only", "GPL-3.0-or-later",
    "Proprietary", "Commercial",
})


def _canonical(candidate: "ApiCandidate") -> bytes:
    payload = {
        "provider": candidate.provider,
        "model": candidate.model,
        "endpoint": candidate.endpoint,
        "capabilities": list(candidate.capabilities),
        "fallback_group": candidate.fallback_group,
        "source": candidate.source,
        "license": candidate.license,
        "cost_per_call": candidate.cost_per_call,
        "credential_ref": candidate.credential_ref,
    }
    return json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()


@dataclass(frozen=True)
class ApiCandidate:
    provider: str
    model: str
    endpoint: str
    capabilities: tuple[str, ...]
    fallback_group: str
    source: str
    license: str = ""
    cost_per_call: float = 0.0
    credential_ref: str | None = None
    # Appended fields preserve all existing positional construction.
    signature: str = ""
    signature_key_id: str = ""
    source_commit: str = ""
    provenance: tuple[str, ...] = ()

    def signing_payload(self) -> bytes:
        return _canonical(self)


class ApiDiscovery:
    def __init__(
        self,
        catalog: DynamicApiCatalog,
        probe: Callable[[ApiCandidate], bool] | None = None,
        *,
        signature_verifier: SignatureVerifier | None = None,
        allowed_licenses: Iterable[str] = _LICENSES,
        require_signature: bool = False,
    ):
        self.catalog = catalog
        self.probe = probe or (lambda candidate: True)
        self.signature_verifier = signature_verifier
        self.allowed_licenses = frozenset(allowed_licenses)
        self.require_signature = require_signature

    @staticmethod
    def _valid_provenance(candidate: ApiCandidate) -> bool:
        values = (candidate.source, *candidate.provenance)
        if not candidate.source or any(not isinstance(value, str) or not value.strip() or any(ord(char) < 32 for char in value) for value in values):
            return False
        # A source URL is optional for legacy catalogs, but if declared it
        # must be immutable-looking and use a safe scheme.
        for value in candidate.provenance:
            if value.startswith(("javascript:", "data:", "file:")):
                return False
        return True

    def _valid_signature(self, candidate: ApiCandidate) -> tuple[bool, bool]:
        has_signature = bool(candidate.signature or candidate.signature_key_id)
        if not has_signature:
            return (not self.require_signature and self.signature_verifier is None, False)
        if not candidate.signature or not candidate.signature_key_id:
            return False, False
        if self.signature_verifier is not None:
            try:
                verified = self.signature_verifier.verify(candidate.signature_key_id, candidate.signing_payload(), bytes.fromhex(candidate.signature))
            except (TypeError, ValueError):
                verified = False
            return verified, verified
        # Without an authority, accept only a deterministic digest. This is
        # useful for catalogs that sign immutable metadata with a content hash,
        # while avoiding trust in a caller-provided `verified` boolean.
        return candidate.signature == sha256(candidate.signing_payload()).hexdigest(), candidate.signature == sha256(candidate.signing_payload()).hexdigest()

    def _valid(self, candidate: ApiCandidate) -> tuple[bool, bool]:
        if not all(isinstance(value, str) and value.strip() for value in (candidate.provider, candidate.model, candidate.endpoint, candidate.fallback_group)):
            return False, False
        if not candidate.capabilities or any(not isinstance(capability, str) or not capability.strip() for capability in candidate.capabilities):
            return False, False
        if not self._valid_provenance(candidate):
            return False, False
        if not candidate.license or candidate.license not in self.allowed_licenses or candidate.cost_per_call < 0:
            return False, False
        if candidate.credential_ref is not None and (not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]{0,127}", candidate.credential_ref)):
            return False, False
        signature_ok, verified = self._valid_signature(candidate)
        return signature_ok and bool(self.probe(candidate)), verified

    def ingest(self, candidates: Iterable[ApiCandidate]) -> tuple[ApiCatalogEntry, ...]:
        registered: list[ApiCatalogEntry] = []
        for candidate in candidates:
            try:
                valid, signature_verified = self._valid(candidate)
            except Exception:
                valid, signature_verified = False, False
            if not valid:
                continue
            provenance = tuple(dict.fromkeys((candidate.source, *candidate.provenance, candidate.endpoint, datetime.now(timezone.utc).isoformat())))
            entry = ApiCatalogEntry(
                candidate.provider,
                candidate.model,
                tuple(candidate.capabilities),
                candidate.fallback_group,
                cost_per_call=candidate.cost_per_call,
                credential_ref=candidate.credential_ref,
                provenance=provenance,
                license=candidate.license,
                signature=candidate.signature,
                signature_key_id=candidate.signature_key_id,
                signature_verified=signature_verified,
            )
            self.catalog.register(entry)
            registered.append(entry)
        return tuple(registered)
