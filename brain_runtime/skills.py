from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, Mapping, Protocol
import hashlib
import json
import re
import threading


_VERSION_PARTS = re.compile(r"\d+|[A-Za-z]+")


@dataclass(frozen=True)
class SkillManifest:
    id: str
    version: str
    description: str
    triggers: tuple[str, ...]
    exclusions: tuple[str, ...] = ()
    body_path: str = ""
    resources: tuple[str, ...] = ()
    tools: tuple[str, ...] = ()
    required_permissions: tuple[str, ...] = ()
    trust_level: str = "community"
    license: str = ""
    content_hash: str = ""
    source_commit: str = ""
    source_url: str = ""
    signature: str = ""
    signature_key_id: str = ""
    provenance: tuple[str, ...] = ()
    signature_verified: bool = False


class SkillTrustAuthority(Protocol):
    """Autoridade externa que confirma identidade e conteúdo de uma Skill."""

    def verify_manifest(self, manifest: SkillManifest) -> bool: ...
    def verify_content(self, manifest: SkillManifest, content_hash: str) -> bool: ...


class StaticSkillTrustAuthority:
    """Allowlist de hashes/proveniência mantida fora do manifesto da Skill.

    A autoridade deve ser carregada de um canal confiável pelo chamador. Os
    metadados da própria Skill nunca são usados para criar uma aprovação.
    """

    def __init__(self, records: Mapping[tuple[str, str], Mapping[str, object]]):
        self._records = {(str(skill_id), str(version)): dict(record) for (skill_id, version), record in records.items()}

    def verify_manifest(self, manifest: SkillManifest) -> bool:
        record = self._records.get((manifest.id, manifest.version))
        if not record or record.get("content_hash") != manifest.content_hash:
            return False
        for field in ("source_commit", "source_url"):
            if field in record and record[field] != getattr(manifest, field):
                return False
        if "provenance" in record and tuple(record["provenance"]) != tuple(manifest.provenance):
            return False
        return True

    def verify_content(self, manifest: SkillManifest, content_hash: str) -> bool:
        record = self._records.get((manifest.id, manifest.version))
        return bool(record and record.get("content_hash") == content_hash and self.verify_manifest(manifest))


class SkillRegistry:
    def __init__(
        self,
        trusted_levels: Iterable[str] = ("builtin", "verified"),
        quarantine_path: str | Path | None = None,
        *,
        allowed_licenses: Iterable[str] | None = None,
        require_manifest_signature: bool = False,
        trust_authority: SkillTrustAuthority | None = None,
        require_authority: bool = False,
    ):
        self._trusted = set(trusted_levels)
        self._skills: dict[str, SkillManifest] = {}
        self._revoked: set[str] = set()
        self._revoked_versions: set[tuple[str, str]] = set()
        self._lock = threading.RLock()
        self.quarantine_path = Path(quarantine_path) if quarantine_path else None
        self.allowed_licenses = frozenset(allowed_licenses or {"MIT", "Apache-2.0", "BSD-2-Clause", "BSD-3-Clause", "ISC", "MPL-2.0", "LGPL-2.1-only", "LGPL-3.0-only", "GPL-2.0-only", "GPL-3.0-only", "Proprietary", "Commercial"})
        self.require_manifest_signature = require_manifest_signature
        self.trust_authority = trust_authority
        self.require_authority = require_authority
        if self.quarantine_path and self.quarantine_path.exists():
            for line in self.quarantine_path.read_text(encoding="utf-8").splitlines():
                try:
                    item = json.loads(line)
                    skill_id = str(item["skill_id"])
                    version = item.get("version")
                    if version:
                        self._revoked_versions.add((skill_id, str(version)))
                    else:
                        self._revoked.add(skill_id)
                except (ValueError, KeyError, TypeError):
                    continue

    @staticmethod
    def _safe_relative(path: str) -> bool:
        candidate = Path(path)
        return bool(path) and not candidate.is_absolute() and ".." not in candidate.parts

    def _validate_manifest(self, manifest: SkillManifest) -> None:
        if manifest.trust_level not in self._trusted:
            raise PermissionError(f"skill '{manifest.id}' não é confiável")
        if not manifest.id or not manifest.version or not manifest.body_path:
            raise ValueError("manifesto exige id, version e body_path")
        if not self._safe_relative(manifest.body_path):
            raise ValueError("body_path must remain inside the skill root")
        if any(not self._safe_relative(path) for path in (*manifest.resources,)):
            raise ValueError("skill resource path escapes root")
        if manifest.trust_level != "builtin" and not manifest.license:
            raise ValueError("external skill license is required")
        if manifest.license and manifest.license not in self.allowed_licenses:
            raise PermissionError("skill license is not allowlisted")
        if self.require_manifest_signature and not (manifest.signature and manifest.signature_key_id and manifest.signature_verified):
            raise PermissionError("skill manifest signature is required")
        if self.require_authority:
            if self.trust_authority is None or not self.trust_authority.verify_manifest(manifest):
                raise PermissionError("skill trust authority verification is required")

    def register(self, manifest: SkillManifest) -> None:
        with self._lock:
            # A revogação persistida é uma decisão de segurança terminal:
            # rejeite-a antes de outras validações para não revelar ou
            # mascarar o estado de uma skill revogada com erros de manifesto.
            if self.is_revoked(manifest.id, manifest.version):
                raise PermissionError("skill is revoked")
            self._validate_manifest(manifest)
            self._skills[manifest.id] = manifest

    def get(self, skill_id: str) -> SkillManifest:
        with self._lock:
            manifest = self._skills[skill_id]
            if self.is_revoked(manifest.id, manifest.version):
                raise KeyError(skill_id)
            return manifest

    def revoke(self, skill_id: str, reason: str = "", version: str | None = None) -> None:
        with self._lock:
            if version:
                self._revoked_versions.add((skill_id, version))
                current = self._skills.get(skill_id)
                if current is not None and current.version == version:
                    self._skills.pop(skill_id, None)
            else:
                self._revoked.add(skill_id)
                self._skills.pop(skill_id, None)
            if self.quarantine_path:
                self.quarantine_path.parent.mkdir(parents=True, exist_ok=True)
                with self.quarantine_path.open("a", encoding="utf-8") as stream:
                    stream.write(json.dumps({"skill_id": skill_id, "version": version, "reason": reason}, ensure_ascii=False) + "\n")

    def is_revoked(self, skill_id: str, version: str | None = None) -> bool:
        return skill_id in self._revoked or (version is not None and (skill_id, version) in self._revoked_versions)

    def quarantine(self, skill_id: str, reason: str, version: str | None = None) -> None:
        if not self.quarantine_path:
            return
        self.quarantine_path.parent.mkdir(parents=True, exist_ok=True)
        with self._lock, self.quarantine_path.open("a", encoding="utf-8") as stream:
            stream.write(json.dumps({"skill_id": skill_id, "version": version, "reason": reason}, ensure_ascii=False) + "\n")

    def match(self, text: str) -> list[SkillManifest]:
        lowered = text.lower()
        with self._lock:
            return [
                skill for skill in self._skills.values()
                if not self.is_revoked(skill.id, skill.version)
                and any(trigger.lower() in lowered for trigger in skill.triggers)
                and not any(exclusion.lower() in lowered for exclusion in skill.exclusions)
            ]

    def all(self) -> tuple[SkillManifest, ...]:
        with self._lock:
            return tuple(skill for skill in self._skills.values() if not self.is_revoked(skill.id, skill.version))

    def validate_body(self, skill_id: str, base_path: str | Path) -> bool:
        try:
            manifest = self.get(skill_id)
        except (KeyError, PermissionError):
            return False
        root = Path(base_path).resolve()
        body = (root / manifest.body_path).resolve()
        if root == body or root not in body.parents or not body.is_file() or body.is_symlink():
            return False
        try:
            digest = hashlib.sha256(body.read_bytes()).hexdigest()
            if manifest.content_hash and digest != manifest.content_hash:
                return False
            if self.trust_authority and self.require_authority:
                return self.trust_authority.verify_content(manifest, digest)
            return True
        except OSError:
            return False

    def authorize(self, skill_id: str, available_permissions: Iterable[str]) -> bool:
        try:
            manifest = self.get(skill_id)
        except (KeyError, PermissionError):
            return False
        return set(manifest.required_permissions).issubset(set(available_permissions))


class SpecialistRouter:
    def __init__(self, registry: SkillRegistry):
        self.registry = registry

    @staticmethod
    def _version_key(version: str) -> tuple:
        return tuple((0, int(part)) if part.isdigit() else (1, part.lower()) for part in _VERSION_PARTS.findall(version))

    def route(self, objective: str) -> SkillManifest:
        matches = self.registry.match(objective)
        if not matches:
            raise LookupError("nenhuma skill confiável corresponde ao objetivo")
        return sorted(matches, key=lambda skill: (skill.trust_level == "builtin", SpecialistRouter._version_key(skill.version)), reverse=True)[0]
