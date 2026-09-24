from __future__ import annotations
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Iterable
from .models import ApprovalRequired, Decision, PolicyContext, PolicyDecision, new_id

_RISKS = {"LOW", "READ_ONLY", "MEDIUM", "HIGH", "CRITICAL"}

class PolicyBroker:
    """Autoridade única de autorização. Catálogos e dispatchers nunca autorizam."""
    def __init__(self, allowed_capabilities: Iterable[str] = (), actor_capabilities: dict[str, Iterable[str]] | None = None):
        self._allowed = set(allowed_capabilities)
        self._actors = {k: set(v) for k, v in (actor_capabilities or {}).items()}

    def authorize(self, actor: str, capability: str, resource: str, context: PolicyContext) -> PolicyDecision:
        risk = context.risk_class.upper()
        decision, reason = Decision.DENY, "denied by default"
        approval = context.approval
        if risk not in _RISKS:
            reason = f"unknown risk class '{context.risk_class}'"
        elif context.ttl_seconds <= 0:
            reason = "policy TTL must be positive"
        elif any(value < 0 for value in context.budget.values()):
            reason = "budget values cannot be negative"
        elif capability not in self._allowed:
            reason = f"capability '{capability}' is not registered"
        elif actor not in self._actors or capability not in self._actors[actor]:
            reason = f"actor '{actor}' is not authorized for '{capability}'"
        elif not context.sandbox_required and risk not in {"LOW", "READ_ONLY"}:
            reason = "sandbox is mandatory for non-low-risk capability"
        elif capability.startswith("network") and not context.network_allowed:
            reason = "network access is not allowed by policy"
        elif capability.startswith("filesystem") and not context.filesystem_roots:
            reason = "filesystem capability requires an explicit root"
        elif context.expires_at and datetime.now(timezone.utc) >= datetime.fromisoformat(context.expires_at):
            reason = "policy context has expired"
        elif approval is not ApprovalRequired.NONE:
            decision, reason = Decision.ASK, f"explicit {approval.value} approval is required"
        else:
            decision, reason = Decision.ALLOW, f"registered capability authorized for resource '{resource}'"
        expires = datetime.now(timezone.utc) + timedelta(seconds=max(1, context.ttl_seconds))
        return PolicyDecision(new_id("decision"), context.run_id, context.task_id, actor, capability, risk,
            decision, approval, context.sandbox_required, context.network_allowed, context.filesystem_roots,
            dict(context.budget), expires.isoformat(), reason, resource)

    def check(self, decision: PolicyDecision, resource: str | None = None) -> bool:
        if decision.decision is not Decision.ALLOW or decision.is_expired:
            return False
        if resource is not None and decision.filesystem_roots:
            path = Path(resource).resolve()
            if not any(path == root or root in path.parents for root in map(Path, decision.filesystem_roots)):
                return False
        return True

    def with_actor_capability(self, actor: str, capability: str) -> "PolicyBroker":
        actors = {key: set(value) for key, value in self._actors.items()}
        actors.setdefault(actor, set()).add(capability)
        return PolicyBroker(self._allowed | {capability}, actors)
