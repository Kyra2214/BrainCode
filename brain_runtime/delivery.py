from __future__ import annotations
from dataclasses import dataclass
from typing import Callable, Iterable
from .models import ExecutionResult
from .observability import Observability, TraceContext

@dataclass(frozen=True)
class ValidationReport:
    passed: bool; diagnostics: tuple[str, ...] = (); evidence: tuple[str, ...] = ()

class QAGate:
    def __init__(self, checks: Iterable[Callable[[ExecutionResult], str | None]] = ()): self.checks = tuple(checks)
    def validate(self, result: ExecutionResult, required_output: str | None = None) -> ValidationReport:
        diagnostics = []
        if not result.success: diagnostics.append(result.error or "execution failed")
        if required_output and required_output not in str(result.output): diagnostics.append("required output not found")
        if not result.evidence: diagnostics.append("validation evidence is required")
        for check in self.checks:
            message = check(result)
            if message: diagnostics.append(message)
        return ValidationReport(not diagnostics, tuple(diagnostics), result.evidence)

class DeliveryPipeline:
    def __init__(self, qa: QAGate, observability: Observability | None = None): self.qa, self.observability = qa, observability
    def deliver(self, result: ExecutionResult, required_output: str | None = None, context: TraceContext | None = None) -> ExecutionResult:
        span = self.observability.start("delivery.qa", context, {"request_id": result.request_id}) if self.observability and context else None
        try:
            report = self.qa.validate(result, required_output)
            if not report.passed:
                if self.observability: self.observability.increment("delivery.validation_failed"); self.observability.record_delivery(False)
                return ExecutionResult(result.request_id, False, result.output, "; ".join(report.diagnostics), result.evidence, "VALIDATION_FAILED", result.metrics, result.provenance)
            if self.observability: self.observability.record_delivery(True)
            return ExecutionResult(result.request_id, True, result.output, None, report.evidence, "DELIVERED", result.metrics, result.provenance)
        finally:
            if span: self.observability.finish(span, "OK")
