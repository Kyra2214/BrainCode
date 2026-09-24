from __future__ import annotations
from dataclasses import dataclass, field, asdict
from datetime import datetime, timezone
from threading import RLock
from typing import Any
import json
from .models import new_id

@dataclass(frozen=True)
class TraceContext:
    trace_id: str; run_id: str; session_id: str; task_id: str; parent_span_id: str = ""

@dataclass(frozen=True)
class Span:
    span_id: str; context: TraceContext; name: str; started_at: str; ended_at: str = ""; attributes: dict[str, Any] = field(default_factory=dict); status: str = "RUNNING"

class Observability:
    def __init__(self): self._lock = RLock(); self._spans: list[Span] = []; self._counters: dict[str, int] = {}; self._gauges: dict[str, float] = {}; self._alerts: list[dict[str, Any]] = []
    def start(self, name: str, context: TraceContext, attributes: dict[str, Any] | None = None) -> Span:
        span = Span(new_id("span"), context, name, datetime.now(timezone.utc).isoformat(), attributes=dict(attributes or {}))
        with self._lock: self._spans.append(span); self._counters[f"span_started:{name}"] = self._counters.get(f"span_started:{name}", 0) + 1
        return span
    def finish(self, span: Span, status: str = "OK", attributes: dict[str, Any] | None = None) -> Span:
        finished = Span(span.span_id, span.context, span.name, span.started_at, datetime.now(timezone.utc).isoformat(), {**span.attributes, **(attributes or {})}, status)
        with self._lock: self._spans = [item if item.span_id != span.span_id else finished for item in self._spans]; self._counters[f"span_finished:{status}"] = self._counters.get(f"span_finished:{status}", 0) + 1
        return finished
    def increment(self, name: str, value: int = 1) -> None:
        with self._lock: self._counters[name] = self._counters.get(name, 0) + value
    def record_policy(self, decision: str) -> None:
        self.increment(f"policy.decision:{decision}")
        if decision == "DENY": self.increment("policy.denied")
    def record_provider(self, provider: str, *, success: bool, cost: float = 0.0, latency_ms: float = 0.0) -> None:
        prefix = f"provider:{provider}"
        self.increment(f"{prefix}.success" if success else f"{prefix}.failure")
        self.increment("provider.calls")
        self.gauge(f"{prefix}.latency_ms", latency_ms)
        self.gauge(f"{prefix}.cost", cost)
    def record_retry(self, component: str = "pipeline") -> None:
        self.increment(f"{component}.retry")
    def record_delivery(self, delivered: bool) -> None:
        self.increment("delivery.delivered" if delivered else "delivery.rejected")
    def gauge(self, name: str, value: float) -> None:
        with self._lock: self._gauges[name] = float(value)
    def alert(self, name: str, message: str, context: TraceContext | None = None) -> None:
        with self._lock: self._alerts.append({"name": name, "message": message, "trace_id": context.trace_id if context else "", "created_at": datetime.now(timezone.utc).isoformat()})
    def spans(self) -> tuple[Span, ...]:
        with self._lock: return tuple(self._spans)
    def counters(self) -> dict[str, int]:
        with self._lock: return dict(self._counters)
    def gauges(self) -> dict[str, float]:
        with self._lock: return dict(self._gauges)
    def alerts(self) -> tuple[dict[str, Any], ...]:
        with self._lock: return tuple(dict(item) for item in self._alerts)
    def export_json(self) -> str:
        with self._lock: return json.dumps({"spans": [asdict(item) for item in self._spans], "counters": self._counters, "gauges": self._gauges, "alerts": self._alerts}, ensure_ascii=False, sort_keys=True)
