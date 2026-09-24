from __future__ import annotations
from dataclasses import dataclass
from time import monotonic
from .observability import Observability, TraceContext

@dataclass
class InstrumentedPolicy:
    inner: object; telemetry: Observability
    def authorize(self, actor, capability, resource, context):
        span = self.telemetry.start("policy.authorize", TraceContext(context.run_id, context.run_id, "", context.task_id), {"capability": capability})
        try:
            decision = self.inner.authorize(actor, capability, resource, context); self.telemetry.increment(f"policy.decision:{decision.decision.value}"); return decision
        finally: self.telemetry.finish(span, "OK")

@dataclass
class InstrumentedApprovalStore:
    inner: object; telemetry: Observability
    def request(self, decision):
        span = self.telemetry.start("approval.request", TraceContext(decision.run_id, decision.run_id, "", decision.task_id), {"capability": decision.capability})
        try: self.telemetry.increment("approval.requested"); return self.inner.request(decision)
        finally: self.telemetry.finish(span, "OK")
    def decide(self, approval_id, approver, approve, **kwargs):
        result = self.inner.decide(approval_id, approver, approve, **kwargs); self.telemetry.increment("approval.granted" if approve else "approval.denied"); return result
    def __getattr__(self, name): return getattr(self.inner, name)

@dataclass
class InstrumentedPlanner:
    inner: object; telemetry: Observability
    def create(self, task):
        span = self.telemetry.start("planner.create", TraceContext(task.task_id, "", task.session_id, task.task_id))
        try:
            plan = self.inner.create(task); self.telemetry.increment("planner.created"); self.telemetry.gauge("planner.steps", len(plan.steps)); return plan
        finally: self.telemetry.finish(span, "OK")

@dataclass
class InstrumentedRouter:
    inner: object; telemetry: Observability
    def select(self, capability):
        started = monotonic(); provider = self.inner.select(capability); self.telemetry.increment("router.selected"); self.telemetry.gauge("router.last_latency_ms", (monotonic() - started) * 1000); return provider

@dataclass
class InstrumentedCatalog:
    inner: object; telemetry: Observability
    def select(self, capability, *args, **kwargs):
        started = monotonic()
        try: result = self.inner.select(capability, *args, **kwargs); self.telemetry.increment("api.selected"); return result
        finally: self.telemetry.gauge("api.selection_latency_ms", (monotonic() - started) * 1000)
    def __getattr__(self, name): return getattr(self.inner, name)

@dataclass
class InstrumentedLearning:
    inner: object; telemetry: Observability
    def record(self, item):
        result = self.inner.record(item); self.telemetry.increment("learning.recorded"); return result
    def __getattr__(self, name): return getattr(self.inner, name)
