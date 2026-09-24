from __future__ import annotations
import hashlib, json, re, sqlite3, threading
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from .models import ExecutionResult, new_id

_SECRET = re.compile(r"(?i)(api[_-]?key|token|password|secret|authorization)\s*[=:]")
_INJECTION = re.compile(r"(?i)(ignore\s+(?:all\s+)?previous|system\s+message|developer\s+instructions|reveal\s+(?:the\s+)?prompt|jailbreak)")

def _digest(*parts: str) -> str: return hashlib.sha256("\x1f".join(parts).encode()).hexdigest()
def _safe(value: str) -> None:
    if _SECRET.search(value): raise ValueError("secret-like content cannot be persisted")
    if _INJECTION.search(value): raise ValueError("prompt-injection-like content cannot be persisted")

@dataclass(frozen=True)
class LearningRecord:
    record_id: str; run_id: str; task_id: str; problem: str; strategy: str; references: tuple[str, ...]; result: str; quality: float
    cost: float = 0.0; duration_ms: int = 0; errors: tuple[str, ...] = (); validation_evidence: tuple[str, ...] = (); created_at: str = ""
    provider: str = ""; agent: str = ""; skill: str = ""; content_hash: str = ""; version: int = 1

class LearningStore:
    def __init__(self, path: str | Path = ":memory:"):
        self._lock = threading.RLock(); self._db = sqlite3.connect(str(path), check_same_thread=False, isolation_level="DEFERRED")
        self._db.execute("CREATE TABLE IF NOT EXISTS learning (id TEXT PRIMARY KEY, payload TEXT NOT NULL, content_hash TEXT UNIQUE)"); self._db.commit()
    def record(self, item: LearningRecord) -> LearningRecord:
        if not item.validation_evidence: raise ValueError("learning record requires validation evidence")
        if not 0 <= item.quality <= 1 or item.cost < 0 or item.duration_ms < 0: raise ValueError("invalid learning metrics")
        _safe(item.problem); _safe(item.result)
        created = item.created_at or datetime.now(timezone.utc).isoformat(); digest = item.content_hash or _digest(item.problem, item.strategy, item.result, *item.references)
        value = LearningRecord(item.record_id, item.run_id, item.task_id, item.problem, item.strategy, item.references, item.result, item.quality, item.cost, item.duration_ms, item.errors, item.validation_evidence, created, item.provider, item.agent, item.skill, digest, item.version)
        with self._lock: self._db.execute("INSERT OR IGNORE INTO learning VALUES (?, ?, ?)", (value.record_id, json.dumps(value.__dict__), value.content_hash)); self._db.commit()
        return value
    def search(self, problem: str, limit: int = 10) -> list[LearningRecord]:
        rows = self._db.execute("SELECT payload FROM learning WHERE payload LIKE ? ORDER BY rowid DESC LIMIT ?", (f"%{problem}%", limit)).fetchall(); return [LearningRecord(**json.loads(row[0])) for row in rows]
    def count(self) -> int: return self._db.execute("SELECT COUNT(*) FROM learning").fetchone()[0]

class FeedbackLoop:
    def __init__(self, store: LearningStore): self.store = store
    def learn(self, item: LearningRecord, validated: bool, evidence: tuple[str, ...] = ()) -> LearningRecord:
        quality = max(0.0, min(1.0, item.quality + (.1 if validated else -.1))); updated = LearningRecord(new_id("learning"), item.run_id, item.task_id, item.problem, item.strategy, item.references, item.result, quality, item.cost, item.duration_ms, item.errors, tuple(dict.fromkeys((*item.validation_evidence, *evidence))), item.created_at, item.provider, item.agent, item.skill, "", item.version + 1); return self.store.record(updated)

class ExecutionLearningBridge:
    """Converte resultados validados em memória e learning records sem persistir secrets."""
    def __init__(self, memory, learning: LearningStore, retention_days: int | None = None): self.memory, self.learning, self.retention_days = memory, learning, retention_days
    def record(self, *, run_id: str, task_id: str, problem: str, strategy: str, result: ExecutionResult, provider: str = "", agent: str = "", skill: str = "", evidence: tuple[str, ...] = (), cost: float = 0.0, duration_ms: int = 0):
        text = json.dumps(result.output, ensure_ascii=False, sort_keys=True); validation = tuple(dict.fromkeys((*result.evidence, *evidence)))
        if not validation: raise ValueError("execution learning requires validation evidence")
        from .memory import Experience
        experience = self.memory.remember(Experience(new_id("experience"), run_id, task_id, problem, provider or "execution", strategy, text, 1.0 if result.success else 0.0, (result.error,) if result.error else (), validation), retention_days=self.retention_days)
        record = self.learning.record(LearningRecord(new_id("learning"), run_id, task_id, problem, strategy, validation, text, 1.0 if result.success else 0.0, cost, duration_ms, (result.error,) if result.error else (), validation, provider=provider, agent=agent, skill=skill))
        return experience, record
