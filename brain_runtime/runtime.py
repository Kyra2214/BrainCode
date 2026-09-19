from __future__ import annotations
from dataclasses import dataclass
from threading import Event
from time import monotonic
from typing import Callable, Iterable
from .delivery import DeliveryPipeline
from .events import EventStore
from .models import EventType, ExecutionResult, new_id
from .observability import Observability, TraceContext
from .pipeline import BrainPipeline
from .recovery import StateReconstructor, RunState
from .project_intelligence import ProjectScanner, ProjectSnapshot
from .readiness import ReadinessGate, ReadinessReport
from .modes import RuntimeMode, requirements
from .authorization import ExecutionAuthorization, _RUNTIME_ISSUER
from .api_discovery import ApiCandidate, ApiDiscovery

class FaultInjected(RuntimeError): pass

@dataclass(frozen=True)
class RuntimeRunResult:
    run_id: str; results: tuple[ExecutionResult, ...]; delivered: tuple[ExecutionResult, ...]; state: RunState
    readiness: ReadinessReport | None = None

class RuntimeCoordinator:
    """Orquestra pipeline -> QA/delivery -> replay state com falhas observáveis e recuperação segura."""
    def __init__(self, pipeline: BrainPipeline, delivery: DeliveryPipeline, events: EventStore, observability: Observability | None = None, fault_injector: Callable[[str], None] | None = None, project_scanner: ProjectScanner | None = None, readiness_gate: ReadinessGate | None = None, enforce_readiness: bool | None = None, mode: RuntimeMode | str = RuntimeMode.DEVELOPMENT, api_discovery: ApiDiscovery | None = None, api_candidates: Iterable[ApiCandidate] = ()):
        self.pipeline, self.delivery, self.events, self.observability, self.fault_injector = pipeline, delivery, events, observability, fault_injector
        self.mode = RuntimeMode(mode); self.requirements = requirements(self.mode)
        if hasattr(pipeline, "mode") and pipeline.mode is not self.mode:
            raise ValueError("pipeline mode must match coordinator mode")
        if enforce_readiness is False and self.requirements.require_readiness:
            raise ValueError(f"{self.mode.value} mode cannot disable readiness")
        if self.requirements.require_readiness and readiness_gate is None:
            raise ValueError(f"{self.mode.value} mode requires a readiness gate")
        self.project_scanner, self.readiness_gate = project_scanner, readiness_gate
        self.api_discovery = api_discovery
        self.discovered_providers = api_discovery.ingest(tuple(api_candidates)) if api_discovery else ()
        if self.discovered_providers:
            self.events.append("runtime", "runtime", "runtime", "ProvidersDiscovered", {"count": len(self.discovered_providers), "providers": [f"{item.provider}/{item.model}" for item in self.discovered_providers]})
        self.enforce_readiness = self.requirements.require_readiness if enforce_readiness is None else enforce_readiness
        self.execution_authorization = ExecutionAuthorization._issue(pipeline, self.mode, issuer=_RUNTIME_ISSUER)
    def _fault(self, stage: str) -> None:
        if self.fault_injector: self.fault_injector(stage)
    def run(self, objective: str, session_id: str, actor: str = "brain", cancel_event: Event | None = None, timeout_seconds: float | None = None) -> RuntimeRunResult:
        started = monotonic(); run_id = ""; delivered: list[ExecutionResult] = []; results: list[ExecutionResult] = []; interrupted = False; snapshot: ProjectSnapshot | None = None; readiness = None
        try:
            if self.project_scanner:
                snapshot = self.project_scanner.scan(); self.project_scanner.write_context(snapshot)
            self._fault("before_pipeline"); results = self.pipeline.run(objective, session_id, actor, authorization=self.execution_authorization); events = self.events.all(); run_id = events[-1].run_id if events else new_id("run"); task_id = events[-1].task_id if events else ""
            if snapshot: self.events.append(run_id, session_id, task_id, "ProjectScanned", {"files": snapshot.architecture.get("file_count", 0), "modules": len(snapshot.modules), "risks": len(snapshot.risks)})
            self._fault("after_pipeline")
            for result in results:
                if cancel_event is not None and cancel_event.is_set():
                    self.events.append(run_id, session_id, task_id, EventType.JOB_CANCELLED, {"stage": "delivery", "request_id": result.request_id}); interrupted = True; break
                if timeout_seconds is not None and monotonic() - started > timeout_seconds:
                    self.events.append(run_id, session_id, task_id, EventType.JOB_FAILED, {"stage": "delivery", "request_id": result.request_id, "error": "runtime timeout"}); interrupted = True; break
                self._fault("before_delivery"); context = TraceContext(run_id, run_id, session_id, task_id); delivered_result = self.delivery.deliver(result, context=context); delivered.append(delivered_result); self._fault("after_delivery")
            if not interrupted and self.readiness_gate:
                readiness = self.readiness_gate.evaluate({"implementation": bool(results), "tests": bool(snapshot.tests) if snapshot else True, "qa": bool(delivered) and all(item.success for item in delivered), "security": not any(item.status == "DENIED" for item in results), "architecture": True, "regression": all(item.success for item in delivered or results), "release": True}, warnings=snapshot.risks if snapshot else ())
                self.events.append(run_id, session_id, task_id, "ReadinessEvaluated", {"status": readiness.status, "score": readiness.score, "critical": readiness.critical, "warnings": readiness.warnings})
                if self.enforce_readiness and readiness.status != "ready":
                    self.events.append(run_id, session_id, task_id, EventType.JOB_FAILED, {"success": False, "stage": "readiness", "blockers": readiness.blockers}); interrupted = True
            if not interrupted: self.events.append(run_id, session_id, task_id, EventType.JOB_COMPLETED, {"success": all(item.success for item in delivered) if delivered else all(item.success for item in results)})
        except FaultInjected as error:
            if run_id:
                self.events.append(run_id, session_id, task_id, EventType.JOB_FAILED, {"error": str(error), "stage": "fault_injection"})
            raise
        except Exception as error:
            if run_id:
                self.events.append(run_id, session_id, task_id, EventType.JOB_FAILED, {"error": str(error), "stage": "runtime"})
            raise
        state = StateReconstructor(self.events).reconstruct(run_id)
        return RuntimeRunResult(run_id, tuple(results), tuple(delivered), state, readiness)
    def recover(self, run_id: str, task_id: str | None = None) -> RunState:
        return StateReconstructor(self.events).reconstruct(run_id, task_id)
    def replay(self, run_id: str, task_id: str | None = None) -> tuple:
        return self.events.replay(run_id, task_id)
