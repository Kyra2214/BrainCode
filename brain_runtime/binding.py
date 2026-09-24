from __future__ import annotations
from dataclasses import dataclass
import hashlib, json
from typing import Any

@dataclass(frozen=True)
class ExecutionBinding:
    run_id: str; task_id: str; step_id: str; capability: str; provider: str; policy_decision_id: str; resource: str = ""; authorization_hash: str = ""
    def digest(self) -> str:
        payload = json.dumps(self.__dict__, sort_keys=True, separators=(",", ":"))
        return hashlib.sha256(payload.encode()).hexdigest()
    def verify(self, digest: str) -> bool: return bool(digest) and digest == self.digest()

def bind_execution(*, run_id: str, task_id: str, step_id: str, capability: str, provider: str, policy_decision_id: str, resource: str = "") -> ExecutionBinding:
    values = (run_id, task_id, step_id, capability, provider, policy_decision_id)
    if any(not isinstance(value, str) or not value for value in values): raise ValueError("execution binding fields are required")
    return ExecutionBinding(run_id, task_id, step_id, capability, provider, policy_decision_id, resource)


def verify_resume_binding(binding: ExecutionBinding, *, run_id: str, task_id: str, step_id: str,
                          capability: str, provider: str, policy_decision_id: str,
                          resource: str = "", digest: str) -> None:
    """Fail closed when an approval tries to resume a changed plan."""
    expected = ExecutionBinding(run_id, task_id, step_id, capability, provider,
                                policy_decision_id, resource, binding.authorization_hash)
    if binding != expected or not binding.verify(digest):
        raise PermissionError("approval binding does not match the authorized execution")
