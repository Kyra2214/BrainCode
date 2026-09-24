from __future__ import annotations

from dataclasses import dataclass
from .events import EventStore
from .models import EventType
from .sandbox import QAGate, SandboxExecutor, SandboxJob


@dataclass(frozen=True)
class Delivery:
    delivered: bool
    result: object
    diagnostics: tuple[str, ...]


class Orchestrator:
    def __init__(self, executor: SandboxExecutor, qa: QAGate, events: EventStore):
        self.executor, self.qa, self.events = executor, qa, events

    def execute(self, job: SandboxJob, task_id: str, required_output: str | None = None) -> Delivery:
        self.events.append(job.run_id, job.session_id, task_id, EventType.JOB_STARTED, {"job_id": job.job_id})
        result = self.executor.execute(job)
        self.events.append(job.run_id, job.session_id, task_id, EventType.JOB_COMPLETED if result.status == "SUCCEEDED" else EventType.JOB_FAILED, {"job_id": job.job_id, "status": result.status})
        passed, diagnostics = self.qa.validate(result, required_output)
        if passed:
            self.events.append(job.run_id, job.session_id, task_id, EventType.VALIDATION_PASSED, {"job_id": job.job_id})
            self.events.append(job.run_id, job.session_id, task_id, EventType.DELIVERED, {"job_id": job.job_id})
        else:
            self.events.append(job.run_id, job.session_id, task_id, EventType.VALIDATION_FAILED, {"job_id": job.job_id, "diagnostics": diagnostics})
        return Delivery(passed, result, diagnostics)
