from __future__ import annotations
from dataclasses import dataclass
from .events import EventStore
from .models import EventType

@dataclass(frozen=True)
class RunState:
    run_id: str
    task_id: str
    status: str
    completed_steps: tuple[str, ...] = ()
    failed_steps: tuple[str, ...] = ()
    delivered: bool = False

class StateReconstructor:
    def __init__(self, events: EventStore): self.events = events
    def reconstruct(self, run_id: str, task_id: str | None = None) -> RunState:
        events = self.events.replay(run_id, task_id)
        if not events: raise KeyError(run_id)
        completed, failed, status, delivered = [], [], "RUNNING", False
        for event in events:
            payload = event.payload; step = payload.get("step_id") or payload.get("job_id") or payload.get("request_id")
            if event.type in {EventType.AGENT_COMPLETED.value, EventType.JOB_COMPLETED.value} and step: completed.append(str(step))
            if event.type in {EventType.AGENT_COMPLETED.value, EventType.JOB_FAILED.value, EventType.VALIDATION_FAILED.value} and payload.get("success") is False and step: failed.append(str(step))
            if event.type == EventType.DELIVERED.value: delivered = True; status = "DELIVERED"
            elif event.type == EventType.JOB_CANCELLED.value: status = "CANCELLED"
            elif event.type == EventType.JOB_FAILED.value: status = "FAILED"
            elif event.type == EventType.JOB_COMPLETED.value: status = "COMPLETED" if payload.get("success", True) else "FAILED"
            elif event.type == EventType.VALIDATION_FAILED.value: status = "NEEDS_CORRECTION"
        if not delivered and not failed and events[-1].type in {EventType.AGENT_COMPLETED.value, EventType.VALIDATION_PASSED.value}: status = "COMPLETED"
        return RunState(run_id, events[0].task_id, status, tuple(dict.fromkeys(completed)), tuple(dict.fromkeys(failed)), delivered)

    def recoverable(self, run_id: str, task_id: str | None = None) -> bool:
        return self.reconstruct(run_id, task_id).status in {"RUNNING", "NEEDS_CORRECTION"}
