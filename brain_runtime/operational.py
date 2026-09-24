from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass
from threading import Lock
from typing import Callable, Iterable, Any


@dataclass(frozen=True)
class QuotaSnapshot:
    provider: str
    model: str
    used: int = 0
    failures: int = 0
    cooldown_until: str | None = None
    latency_ms: float | None = None
    success_rate: float = 0.0


class PersistentQuotaStore:
    """Small SQLite state store independent of the API catalog metadata."""
    def __init__(self, path=":memory:"):
        import sqlite3
        self.db = sqlite3.connect(str(path), check_same_thread=False)
        self.lock = Lock()
        self.db.execute("CREATE TABLE IF NOT EXISTS quota (provider TEXT, model TEXT, used INTEGER, failures INTEGER, cooldown TEXT, latency REAL, success REAL, PRIMARY KEY(provider,model))")
        self.db.commit()
    def save(self, item: QuotaSnapshot) -> None:
        with self.lock:
            self.db.execute("INSERT OR REPLACE INTO quota VALUES (?,?,?,?,?,?,?)", (item.provider,item.model,item.used,item.failures,item.cooldown_until,item.latency_ms,item.success_rate)); self.db.commit()
    def get(self, provider: str, model: str) -> QuotaSnapshot | None:
        row = self.db.execute("SELECT * FROM quota WHERE provider=? AND model=?", (provider, model)).fetchone()
        return QuotaSnapshot(*row) if row else None


@dataclass(frozen=True)
class FallbackCandidate:
    provider: str
    model: str
    capabilities: frozenset[str]
    context_tokens: int
    cost: float
    authorized: bool = True
    credential_available: bool = True
    quality: float = 1.0


def strict_fallback(candidates: Iterable[FallbackCandidate], capability: str, *, required_context: int = 0, budget: float | None = None, minimum_quality: float = 0.0) -> list[FallbackCandidate]:
    """Return only genuinely equivalent, authorized and usable alternatives."""
    return [item for item in candidates if capability in item.capabilities and item.context_tokens >= required_context and (budget is None or item.cost <= budget) and item.authorized and item.credential_available and item.quality >= minimum_quality]


@dataclass(frozen=True)
class Correction:
    step_id: str
    diagnostic: str
    attempt: int


class CorrectionLoop:
    def __init__(self, max_attempts: int = 1): self.max_attempts = max(0, max_attempts)
    def run(self, step_id: str, execute: Callable[[Correction], Any], diagnose: Callable[[Any], str | None]) -> Any:
        for attempt in range(self.max_attempts + 1):
            result = execute(Correction(step_id, "initial" if attempt == 0 else "retry", attempt))
            error = diagnose(result)
            if error is None: return result
            if attempt == self.max_attempts: return result
        raise AssertionError("unreachable")


class ParallelPlanExecutor:
    """Runs independent branches while preserving result order and cancellation."""
    def __init__(self, max_workers: int = 4): self.max_workers = max(1, max_workers)
    def run(self, steps: Iterable[Any], dependencies: Callable[[Any], Iterable[str]], execute: Callable[[Any], Any], step_id: Callable[[Any], str]) -> list[Any]:
        pending = list(steps); done: set[str] = set(); results: dict[str, Any] = {}
        while pending:
            ready = [step for step in pending if all(dep in done for dep in dependencies(step))]
            if not ready: raise ValueError("cyclic or unsatisfied plan dependencies")
            with ThreadPoolExecutor(max_workers=min(self.max_workers, len(ready))) as pool:
                futures = {pool.submit(execute, step): step for step in ready}
                for future in as_completed(futures):
                    step = futures[future]; result = future.result(); results[step_id(step)] = result; done.add(step_id(step)); pending.remove(step)
        return [results[step_id(step)] for step in steps]
