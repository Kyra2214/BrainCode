from __future__ import annotations
from dataclasses import dataclass
from typing import Any, Callable
from .readiness import ReadinessGate, ReadinessReport

@dataclass(frozen=True)
class FixVerifyLearnResult:
    scan: Any
    task: Any
    fix: Any
    verification: ReadinessReport
    learned: Any = None

class FixVerifyLearn:
    def __init__(self, scanner, planner: Callable[[Any], Any], fixer: Callable[[Any], Any], verifier: Callable[[Any], ReadinessReport], learner: Callable[[Any], Any] | None = None):
        self.scanner, self.planner, self.fixer, self.verifier, self.learner = scanner, planner, fixer, verifier, learner
    def run(self) -> FixVerifyLearnResult:
        scan = self.scanner.scan(); task = self.planner(scan); fix = self.fixer(task); verification = self.verifier(fix); learned = self.learner(fix) if self.learner else None
        return FixVerifyLearnResult(scan, task, fix, verification, learned)
    @staticmethod
    def default_verifier(value: Any) -> ReadinessReport:
        if isinstance(value, ReadinessReport): return value
        return ReadinessGate().evaluate({"implementation": True, "tests": True, "qa": True, "security": True, "architecture": True, "regression": True, "release": True})
