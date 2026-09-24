from __future__ import annotations
from dataclasses import asdict, dataclass, field
from datetime import datetime, timezone
from enum import Enum
from typing import Any
from uuid import uuid4


def now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()

class Decision(str, Enum):
    ALLOW = "ALLOW"
    ASK = "ASK"
    DENY = "DENY"

class ApprovalRequired(str, Enum):
    NONE = "NONE"
    USER = "USER"
    ADMIN = "ADMIN"

class EventType(str, Enum):
    TASK_CREATED="TaskCreated"; TASK_CLASSIFIED="TaskClassified"; PLAN_CREATED="PlanCreated"
    CAPABILITY_SELECTED="CapabilitySelected"; POLICY_CHECKED="PolicyChecked"; APPROVAL_REQUESTED="ApprovalRequested"
    APPROVAL_GRANTED="ApprovalGranted"; APPROVAL_DENIED="ApprovalDenied"
    AGENT_DISPATCHED="AgentDispatched"; AGENT_COMPLETED="AgentCompleted"; VALIDATION_STARTED="ValidationStarted"
    VALIDATION_FAILED="ValidationFailed"; CORRECTION_REQUESTED="CorrectionRequested"; RETRY="Retry"
    VALIDATION_PASSED="ValidationPassed"; DELIVERED="Delivered"; JOB_STARTED="JobStarted"
    JOB_APPROVAL_REQUESTED="JobApprovalRequested"; JOB_COMPLETED="JobCompleted"; JOB_FAILED="JobFailed"; JOB_CANCELLED="JobCancelled"

@dataclass(frozen=True)
class TaskSpec:
    task_id: str; session_id: str; objective: str
    constraints: tuple[str, ...] = (); inputs: dict[str, Any] = field(default_factory=dict)
    expected_outputs: tuple[str, ...] = (); success_criteria: tuple[str, ...] = ()
    capabilities: tuple[str, ...] = (); priority: int = 50
    context: dict[str, Any] = field(default_factory=dict); requested_output: str = ""

@dataclass(frozen=True)
class PolicyContext:
    run_id: str; task_id: str; actor: str; risk_class: str = "LOW"
    approval: ApprovalRequired = ApprovalRequired.NONE; sandbox_required: bool = True
    network_allowed: bool = False; filesystem_roots: tuple[str, ...] = ()
    budget: dict[str, int] = field(default_factory=dict); ttl_seconds: int = 300
    resource: str = ""; expires_at: str | None = None

@dataclass(frozen=True)
class PolicyDecision:
    decision_id: str; run_id: str; task_id: str; actor: str; capability: str; risk_class: str
    decision: Decision; approval_required: ApprovalRequired; sandbox_required: bool
    network_allowed: bool; filesystem_roots: tuple[str, ...]; budget: dict[str, int]
    expires_at: str; reason: str; resource: str = ""
    @property
    def is_expired(self) -> bool:
        return datetime.now(timezone.utc) >= datetime.fromisoformat(self.expires_at)

@dataclass(frozen=True)
class Event:
    event_id: str; run_id: str; session_id: str; task_id: str; timestamp: str; type: str
    version: int; sequence: int; payload: dict[str, Any]; redacted: bool = True
    previous_hash: str = ""; hash: str = ""; idempotency_key: str | None = None; correlation_id: str = ""

@dataclass(frozen=True)
class Capability:
    capability_id: str; description: str; risk_class: str = "LOW"
    requires_network: bool = False; requires_approval: bool = False

@dataclass(frozen=True)
class PlanStep:
    step_id: str; description: str; capability: str; inputs: dict[str, Any] = field(default_factory=dict)
    timeout_seconds: int = 60; retry_limit: int = 1; parent_step_id: str | None = None; fallback: str | None = None
    dependencies: tuple[str, ...] = (); expected_outputs: tuple[str, ...] = (); risk_class: str = "LOW"
    validation_criteria: tuple[str, ...] = ()

@dataclass(frozen=True)
class Plan:
    plan_id: str; task_id: str; steps: tuple[PlanStep, ...]
    validation_strategy: str = "qa_gate"; delivery_criteria: tuple[str, ...] = ()

@dataclass(frozen=True)
class ExecutionRequest:
    request_id: str; run_id: str; task_id: str; step_id: str; capability: str; objective: str
    inputs: dict[str, Any] = field(default_factory=dict); policy_decision_id: str = ""
    timeout_seconds: int = 60; budget: dict[str, int] = field(default_factory=dict); idempotency_key: str = ""

@dataclass(frozen=True)
class ExecutionResult:
    request_id: str; success: bool; output: dict[str, Any] = field(default_factory=dict)
    error: str | None = None; evidence: tuple[str, ...] = (); status: str = "SUCCEEDED"
    metrics: dict[str, Any] = field(default_factory=dict); provenance: tuple[str, ...] = ()

def new_id(prefix: str) -> str: return f"{prefix}_{uuid4().hex}"
def to_dict(value: Any) -> dict[str, Any]: return asdict(value)
