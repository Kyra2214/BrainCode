from __future__ import annotations
from dataclasses import dataclass
from typing import Callable, Iterable
from .models import Decision, PolicyContext, ExecutionResult

@dataclass(frozen=True)
class FallbackAttempt:
    provider: str; decision_id: str; success: bool; error: str = ""

class AuthorizedFallback:
    def __init__(self, policy, capability: str, actor: str, run_id: str, task_id: str, idempotency: dict[str, ExecutionResult] | None = None): self.policy, self.capability, self.actor, self.run_id, self.task_id, self.idempotency, self.attempts = policy, capability, actor, run_id, task_id, idempotency or {}, []
    def execute(self, providers: Iterable[str], call: Callable[[str, str], ExecutionResult], *, network_allowed: bool = False) -> ExecutionResult:
        last = None
        for provider in providers:
            key = f"{self.run_id}:{self.task_id}:{self.capability}:{provider}"
            if key in self.idempotency: return self.idempotency[key]
            decision = self.policy.authorize(self.actor, self.capability, provider, PolicyContext(self.run_id, self.task_id, self.actor, network_allowed=network_allowed))
            if decision.decision is not Decision.ALLOW:
                self.attempts.append(FallbackAttempt(provider, decision.decision_id, False, decision.reason)); last = PermissionError(decision.reason); continue
            try:
                result = call(provider, decision.decision_id); self.attempts.append(FallbackAttempt(provider, decision.decision_id, result.success, result.error or ""));
                if result.success: self.idempotency[key] = result; return result
                last = RuntimeError(result.error or "provider failed")
            except Exception as error:
                self.attempts.append(FallbackAttempt(provider, decision.decision_id, False, str(error))); last = error
        if last: raise RuntimeError("all authorized equivalent providers failed") from last
        raise LookupError("no fallback providers supplied")
