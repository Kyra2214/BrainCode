from __future__ import annotations
from dataclasses import dataclass, asdict
from typing import Iterable
import hashlib, re

_SECRET = re.compile(r"(?i)(api[_-]?key|token|password|secret)\s*[=:]")
_INJECTION = re.compile(r"(?i)(ignore\s+(?:all\s+)?previous|system\s+message|developer\s+instructions|jailbreak)")

@dataclass(frozen=True)
class Evidence:
    source: str
    statement: str
    kind: str = "repository"
    verified: bool = False
    digest: str = ""
    def __post_init__(self):
        if not self.source or not self.statement: raise ValueError("evidence requires source and statement")
        if _SECRET.search(self.statement) or _INJECTION.search(self.statement): raise ValueError("unsafe evidence")
        expected = hashlib.sha256(self.statement.encode()).hexdigest()
        if self.digest and self.digest != expected: raise ValueError("evidence digest mismatch")
        if not self.digest: object.__setattr__(self, "digest", expected)

@dataclass(frozen=True)
class ClaimAssessment:
    claim: str
    evidence: tuple[Evidence, ...]
    external_state: str
    confidence: float
    decision: str
    missing: tuple[str, ...] = ()
    def to_dict(self) -> dict: return asdict(self)

class EvidenceEngine:
    def assess(self, claim: str, evidence: Iterable[Evidence], *, required_kinds: Iterable[str] = (), external_state: str = "unknown") -> ClaimAssessment:
        if not claim or _SECRET.search(claim) or _INJECTION.search(claim): raise ValueError("unsafe claim")
        items = tuple(evidence); kinds = {item.kind for item in items if item.verified}; missing = tuple(kind for kind in required_kinds if kind not in kinds)
        verified = len([item for item in items if item.verified]); confidence = min(1.0, verified / max(1, len(tuple(required_kinds)) or 1)) * (0.75 if external_state == "unknown" else 1.0)
        decision = "blocked" if missing or (external_state == "unknown" and confidence < .8) else "supported"
        return ClaimAssessment(claim, items, external_state, round(confidence, 3), decision, missing)
