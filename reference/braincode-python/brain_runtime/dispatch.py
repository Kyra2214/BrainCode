from __future__ import annotations
from dataclasses import dataclass
from typing import Callable
from .binding import bind_execution
from .models import ExecutionRequest, ExecutionResult
from .sandbox import SandboxExecutor, SandboxJob
from time import monotonic
from .credentials import CredentialRef, CredentialVault

@dataclass
class SandboxDispatcher:
    executor: SandboxExecutor
    job_factory: Callable[[ExecutionRequest], SandboxJob]
    def dispatch(self, request: ExecutionRequest) -> ExecutionResult:
        digest = request.inputs.get("binding_digest") if isinstance(request.inputs, dict) else None
        binding = bind_execution(run_id=request.run_id, task_id=request.task_id, step_id=request.step_id, capability=request.capability, provider=request.inputs.get("provider", "") if isinstance(request.inputs, dict) else "", policy_decision_id=request.policy_decision_id, resource=request.inputs.get("provider", "") if isinstance(request.inputs, dict) else "")
        if not binding.verify(digest): return ExecutionResult(request.request_id, False, error="execution binding mismatch", status="REJECTED")
        job = self.job_factory(request)
        if job.run_id != request.run_id or job.session_id == "": return ExecutionResult(request.request_id, False, error="sandbox request correlation mismatch", status="REJECTED")
        result = self.executor.execute(job)
        return ExecutionResult(request.request_id, result.status == "SUCCEEDED", {"stdout": result.stdout, "artifacts": result.artifacts}, None if result.status == "SUCCEEDED" else "; ".join(result.diagnostics), evidence=tuple(result.diagnostics), status=result.status, metrics={"duration_ms": result.duration_ms}, provenance=(f"sandbox:{job.job_id}",))

@dataclass
class RoutedDispatcher:
    catalog: object
    dispatcher: object
    def dispatch(self, request: ExecutionRequest) -> ExecutionResult:
        provider = request.inputs.get("provider", "") if isinstance(request.inputs, dict) else ""
        if "/" not in provider: return ExecutionResult(request.request_id, False, error="provider binding is invalid", status="REJECTED")
        name, model = provider.split("/", 1); started = monotonic()
        try:
            self.catalog.reserve(name, model); entry = next(item for item in self.catalog._entries if item.provider == name and item.model == model); result = self.dispatcher.dispatch(request); elapsed = (monotonic() - started) * 1000; self.catalog.reconcile(name, model); self.catalog.record(name, model, result.success, elapsed); return ExecutionResult(result.request_id, result.success, result.output, result.error, result.evidence, result.status, {**result.metrics, "duration_ms": elapsed, "provider": provider, "cost": entry.cost_per_call}, result.provenance)
        except Exception:
            try: self.catalog.reconcile(name, model)
            except Exception: pass
            raise

@dataclass
class CredentialDispatcher:
    vault: CredentialVault
    dispatcher: object
    credential_ref: CredentialRef | None = None
    def dispatch(self, request: ExecutionRequest) -> ExecutionResult:
        return self.vault.dispatch(self.dispatcher.dispatch, request, self.credential_ref)
