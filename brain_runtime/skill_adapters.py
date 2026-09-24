from __future__ import annotations

from dataclasses import dataclass
from hashlib import sha256
from pathlib import Path
from typing import Callable
import re
from urllib.parse import urlparse

from .skills import SkillManifest, SkillRegistry
from .signatures import SignatureVerifier


@dataclass(frozen=True)
class ExternalSkill:
    source: str
    external_id: str
    version: str
    description: str
    body: str
    license: str
    permissions: tuple[str, ...] = ()
    verified: bool = False
    source_url: str = ""
    commit: str = ""
    signature: str = ""
    key_id: str = ""


class ExternalSkillAdapter:
    source_name = "external"
    allowed_licenses = frozenset({"MIT", "Apache-2.0", "BSD-2-Clause", "BSD-3-Clause", "ISC", "MPL-2.0", "LGPL-2.1-only", "LGPL-3.0-only", "GPL-2.0-only", "GPL-3.0-only", "Proprietary", "Commercial"})

    def __init__(
        self,
        scanner: Callable[[str], bool] | None = None,
        allow_licenses: set[str] | None = None,
        signature_verifier: SignatureVerifier | None = None,
        *,
        require_provenance: bool = False,
        require_signature: bool = False,
    ):
        self.scanner = scanner or self.scan_body
        self.allow_licenses = frozenset(allow_licenses or self.allowed_licenses)
        self.signature_verifier = signature_verifier
        self.require_provenance = require_provenance
        self.require_signature = require_signature

    @staticmethod
    def scan_body(body: str) -> bool:
        return not re.search(r"(?i)(os\.system|subprocess|eval\s*\(|exec\s*\(|ignore\s+previous|api[_-]?key\s*=|curl\s+|wget\s+)", body)

    @staticmethod
    def _safe_source(value: str) -> bool:
        return bool(value and value.strip() and not any(ord(char) < 32 for char in value))

    @staticmethod
    def _safe_url(value: str) -> bool:
        if not value:
            return True
        try:
            parsed = urlparse(value)
            return parsed.scheme in {"https", "http"} and bool(parsed.netloc) and not any(ord(char) < 32 for char in value)
        except ValueError:
            return False

    @staticmethod
    def _safe_commit(value: str) -> bool:
        return not value or bool(re.fullmatch(r"[A-Za-z0-9._/-]{4,256}", value))

    def _signature_ok(self, skill: ExternalSkill) -> bool:
        present = bool(skill.signature or skill.key_id or skill.commit)
        if not present:
            return not self.require_signature and skill.verified
        if not skill.commit or not skill.signature:
            return False
        try:
            raw_signature = bytes.fromhex(skill.signature)
        except (TypeError, ValueError):
            return False
        if self.signature_verifier is not None:
            if not skill.key_id:
                return False
            return self.signature_verifier.verify(skill.key_id, skill.body.encode(), raw_signature)
        # Legacy adapters used a deterministic commit+body digest. Keep it as
        # a compatibility path while never trusting only the `verified` bit
        # when signed metadata is present.
        return skill.signature == sha256((skill.commit + skill.body).encode()).hexdigest()

    def import_skill(self, skill: ExternalSkill, registry: SkillRegistry, destination: str | Path) -> SkillManifest:
        if not skill.external_id or not skill.version or not skill.body.strip() or not self._safe_source(skill.source):
            raise ValueError("external skill metadata is incomplete")
        if not self._safe_url(skill.source_url) or not self._safe_commit(skill.commit):
            registry.quarantine(skill.external_id, "invalid provenance")
            raise PermissionError("skill provenance is invalid")
        if self.require_provenance and (not skill.source_url or not skill.commit):
            registry.quarantine(skill.external_id, "immutable provenance missing")
            raise PermissionError("skill immutable provenance is required")
        if skill.license not in self.allow_licenses:
            registry.quarantine(skill.external_id, "license not allowed")
            raise PermissionError("skill license is not allowlisted")
        if not self.scanner(skill.body):
            registry.quarantine(skill.external_id, "content scan failed")
            raise PermissionError("skill content failed security scan")
        if not self._signature_ok(skill):
            registry.quarantine(skill.external_id, "signature or immutable commit missing")
            raise PermissionError("skill signature verification failed")
        root = Path(destination).resolve()
        root.mkdir(parents=True, exist_ok=True)
        safe_id = "".join(char if char.isalnum() or char in "-_" else "_" for char in skill.external_id)
        if not safe_id:
            raise ValueError("external skill id is invalid")
        body_path = root / f"{safe_id}.md"
        body_path.write_text(skill.body, encoding="utf-8")
        digest = sha256(skill.body.encode()).hexdigest()
        manifest = SkillManifest(
            safe_id,
            skill.version,
            skill.description,
            triggers=(),
            exclusions=(),
            body_path=body_path.name,
            required_permissions=tuple(skill.permissions),
            trust_level="verified",
            license=skill.license,
            content_hash=digest,
            source_commit=skill.commit,
            source_url=skill.source_url,
            signature=skill.signature,
            signature_key_id=skill.key_id,
            provenance=tuple(item for item in (skill.source, skill.source_url, skill.commit) if item),
            signature_verified=bool(skill.signature or skill.verified),
        )
        try:
            registry.register(manifest)
        except Exception:
            try:
                body_path.unlink()
            except OSError:
                pass
            raise
        return manifest


class GitSkillsAdapter(ExternalSkillAdapter):
    source_name = "gitskills"


class AnthropicSkillsAdapter(ExternalSkillAdapter):
    source_name = "anthropic"
