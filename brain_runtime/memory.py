from __future__ import annotations
import hashlib, json, re, sqlite3, threading
from dataclasses import asdict, dataclass
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any

_INJECTION = re.compile(r"(?i)(ignore\s+(?:all\s+)?previous|system\s+message|developer\s+instructions|reveal\s+(?:the\s+)?prompt|jailbreak)")
_SECRET = re.compile(r"(?i)(api[_-]?key|token|password|secret|authorization)\s*[=:]")

def _content_hash(*parts: str) -> str: return hashlib.sha256("\x1f".join(parts).encode()).hexdigest()
def _safe_text(value: str) -> None:
    if _SECRET.search(value): raise ValueError("secret-like content cannot be persisted")
    if _INJECTION.search(value): raise ValueError("prompt-injection-like content cannot be persisted")

@dataclass(frozen=True)
class Experience:
    experience_id: str; run_id: str; task_id: str; problem: str; source: str; strategy: str; result: str; quality: float
    errors: tuple[str, ...] = (); provenance: tuple[str, ...] = (); created_at: str = ""; expires_at: str | None = None
    content_hash: str = ""; version: int = 1

class SQLiteExperienceMemory:
    def __init__(self, path: str | Path = ":memory:"):
        self.path = str(path); self._lock = threading.RLock(); self._connection = sqlite3.connect(self.path, check_same_thread=False, isolation_level="DEFERRED")
        self._connection.execute("PRAGMA journal_mode=WAL")
        self._connection.execute("""CREATE TABLE IF NOT EXISTS experiences (
            experience_id TEXT PRIMARY KEY, run_id TEXT NOT NULL, task_id TEXT NOT NULL, problem TEXT NOT NULL,
            source TEXT NOT NULL, strategy TEXT NOT NULL, result TEXT NOT NULL, quality REAL NOT NULL,
            errors_json TEXT NOT NULL, provenance_json TEXT NOT NULL, created_at TEXT NOT NULL,
            expires_at TEXT, content_hash TEXT NOT NULL DEFAULT '', version INTEGER NOT NULL DEFAULT 1,
            UNIQUE(content_hash))""")
        self._connection.commit()

    def record(self, experience: Experience) -> Experience:
        _safe_text(experience.problem); _safe_text(experience.result)
        if not experience.provenance: raise ValueError("experience requires provenance")
        if not 0 <= experience.quality <= 1: raise ValueError("quality must be between 0 and 1")
        created = experience.created_at or datetime.now(timezone.utc).isoformat()
        digest = experience.content_hash or _content_hash(experience.problem, experience.strategy, experience.result, *experience.provenance)
        value = Experience(experience.experience_id, experience.run_id, experience.task_id, experience.problem, experience.source, experience.strategy, experience.result, experience.quality, experience.errors, experience.provenance, created, experience.expires_at, digest, experience.version)
        with self._lock:
            self._connection.execute("INSERT OR IGNORE INTO experiences VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", (value.experience_id, value.run_id, value.task_id, value.problem, value.source, value.strategy, value.result, value.quality, json.dumps(value.errors), json.dumps(value.provenance), value.created_at, value.expires_at, value.content_hash, value.version)); self._connection.commit()
        return value

    def search(self, problem: str, limit: int = 10) -> list[Experience]:
        if limit <= 0: return []
        now = datetime.now(timezone.utc).isoformat(); rows = self._connection.execute("SELECT * FROM experiences WHERE problem LIKE ? AND (expires_at IS NULL OR expires_at > ?) ORDER BY created_at DESC LIMIT ?", (f"%{problem}%", now, limit)).fetchall()
        return [Experience(row[0], row[1], row[2], row[3], row[4], row[5], row[6], row[7], tuple(json.loads(row[8])), tuple(json.loads(row[9])), row[10], row[11], row[12], row[13]) for row in rows]

    def search_strategy(self, strategy: str, *, problem: str = "", limit: int = 10) -> list[Experience]:
        if limit <= 0: return []
        now = datetime.now(timezone.utc).isoformat()
        rows = self._connection.execute("SELECT * FROM experiences WHERE strategy LIKE ? AND problem LIKE ? AND (expires_at IS NULL OR expires_at > ?) ORDER BY quality DESC, created_at DESC LIMIT ?", (f"%{strategy}%", f"%{problem}%", now, limit)).fetchall()
        return [Experience(row[0], row[1], row[2], row[3], row[4], row[5], row[6], row[7], tuple(json.loads(row[8])), tuple(json.loads(row[9])), row[10], row[11], row[12], row[13]) for row in rows]

    def expire(self, before: str | None = None) -> int:
        cutoff = before or datetime.now(timezone.utc).isoformat()
        with self._lock:
            cursor = self._connection.execute("DELETE FROM experiences WHERE expires_at IS NOT NULL AND expires_at <= ?", (cutoff,)); self._connection.commit(); return cursor.rowcount
    def count(self) -> int: return int(self._connection.execute("SELECT COUNT(*) FROM experiences").fetchone()[0])
    def close(self) -> None: self._connection.close()

class SemanticMemory:
    def __init__(self, backend: SQLiteExperienceMemory): self.backend = backend
    def remember(self, experience: Experience, retention_days: int | None = None) -> Experience:
        if retention_days is not None:
            if retention_days <= 0: raise ValueError("retention_days must be positive")
            experience = Experience(**{**asdict(experience), "expires_at": (datetime.now(timezone.utc) + timedelta(days=retention_days)).isoformat()})
        return self.backend.record(experience)
    def retrieve(self, query: str, limit: int = 10) -> list[Experience]: return self.backend.search(query, limit)

class LearningLoop:
    def __init__(self, memory: SemanticMemory): self.memory = memory
    def record_feedback(self, experience: Experience, validated: bool, evidence: tuple[str, ...] = ()) -> Experience:
        quality = max(0.0, min(1.0, experience.quality + (0.1 if validated else -0.1))); updated = Experience(experience.experience_id, experience.run_id, experience.task_id, experience.problem, experience.source, experience.strategy, experience.result, quality, experience.errors, tuple(dict.fromkeys((*experience.provenance, *evidence))), experience.created_at, experience.expires_at, experience.content_hash, experience.version + 1)
        return self.memory.remember(updated)
