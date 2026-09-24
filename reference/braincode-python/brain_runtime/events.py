from __future__ import annotations
import hashlib, json, os, re, threading, fcntl
from pathlib import Path
from typing import Any, Callable
from .models import Event, EventType, now_iso, new_id

_SECRET_KEYS = {"secret", "token", "password", "api_key", "apikey", "credential", "authorization"}
_SECRET_PATTERN = re.compile(r"(?i)(bearer\s+|sk-|ghp_|api[_-]?key\s*[=:])[^\s,;]+")

def redact(value: Any) -> Any:
    if isinstance(value, dict): return {key: "[REDACTED]" if str(key).lower() in _SECRET_KEYS else redact(item) for key, item in value.items()}
    if isinstance(value, (list, tuple)): return [redact(item) for item in value]
    if isinstance(value, str): return _SECRET_PATTERN.sub(lambda m: m.group(1) + "[REDACTED]", value)
    return value

def _digest(event_data: dict[str, Any]) -> str:
    body = {k: v for k, v in event_data.items() if k != "hash"}
    return hashlib.sha256(json.dumps(body, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()).hexdigest()

def _validate_event(data: dict[str, Any]) -> None:
    required = ("event_id", "run_id", "session_id", "task_id", "timestamp", "type", "version", "sequence", "payload", "previous_hash", "hash")
    missing = [key for key in required if key not in data]
    if missing: raise ValueError(f"event missing fields: {', '.join(missing)}")
    if data["version"] not in (1, 2) or not isinstance(data["payload"], dict): raise ValueError("invalid event schema")
    if data["hash"] and data["hash"] != _digest(data): raise ValueError("event hash mismatch")

class EventStore:
    """Append-only JSONL store with hash chain, streams, rotation, retention and crash recovery."""
    def __init__(self, path: str | Path | None = None, *, max_bytes: int | None = None, retention_events: int | None = None):
        self.path = Path(path) if path else None; self.max_bytes = max_bytes; self.retention_events = retention_events; self._lock = threading.RLock(); self._events = []; self._idempotency = {}; self._stream_sequences = {}
        if self.path and self.path.exists(): self._load(recover_partial=True)
    def _load(self, recover_partial: bool = False) -> None:
        self._events, self._idempotency, self._stream_sequences = [], {}, {}
        with self.path.open("rb") as stream: lines = stream.readlines()
        for number, raw in enumerate(lines, 1):
            if not raw.strip(): continue
            try: data = json.loads(raw)
            except json.JSONDecodeError:
                if recover_partial and number == len(lines) and not raw.endswith(b"\n"):
                    with self.path.open("rb+") as repair: repair.truncate(sum(len(item) for item in lines[:-1]))
                    break
                raise ValueError(f"invalid event at line {number}")
            data.setdefault("correlation_id", data.get("run_id", "")); _validate_event(data); event = Event(**data)
            if self._events and (event.previous_hash != self._events[-1].hash or event.sequence != self._events[-1].sequence + 1): raise ValueError(f"event chain mismatch at line {number}")
            self._events.append(event); self._stream_sequences[(event.run_id, event.task_id)] = self._stream_sequences.get((event.run_id, event.task_id), 0) + 1
            if event.idempotency_key: self._idempotency[event.idempotency_key] = event
    def _rotate_if_needed(self) -> None:
        if not self.path or not self.max_bytes or self.path.stat().st_size <= self.max_bytes: return
        rotated = self.path.with_name(f"{self.path.name}.{datetime_stamp()}"); os.replace(self.path, rotated)
        self._events, self._idempotency, self._stream_sequences = [], {}, {}
    def append(self, run_id: str, session_id: str, task_id: str, event_type: EventType | str, payload: dict[str, Any], idempotency_key: str | None = None, correlation_id: str | None = None) -> Event:
        if not isinstance(payload, dict): raise ValueError("event payload must be an object")
        with self._lock:
            lock_stream = None
            if self.path:
                self.path.parent.mkdir(parents=True, exist_ok=True); lock_stream = self.path.open("a+", encoding="utf-8"); fcntl.flock(lock_stream.fileno(), fcntl.LOCK_EX); self._load(recover_partial=True)
            if idempotency_key and idempotency_key in self._idempotency:
                existing = self._idempotency[idempotency_key]
                if lock_stream: fcntl.flock(lock_stream.fileno(), fcntl.LOCK_UN); lock_stream.close()
                return existing
            event_name = event_type.value if isinstance(event_type, EventType) else str(event_type); previous = self._events[-1].hash if self._events else "GENESIS"
            event = Event(new_id("event"), run_id, session_id, task_id, now_iso(), event_name, 2, len(self._events), redact(payload), True, previous, "", idempotency_key, correlation_id or run_id)
            data = event.__dict__.copy(); data["hash"] = _digest(data); event = Event(**data); _validate_event(event.__dict__)
            if lock_stream:
                lock_stream.seek(0, os.SEEK_END); lock_stream.write(json.dumps(event.__dict__, ensure_ascii=False, sort_keys=True) + "\n"); lock_stream.flush(); os.fsync(lock_stream.fileno()); fcntl.flock(lock_stream.fileno(), fcntl.LOCK_UN); lock_stream.close()
            self._events.append(event); self._stream_sequences[(run_id, task_id)] = self._stream_sequences.get((run_id, task_id), 0) + 1
            if idempotency_key: self._idempotency[idempotency_key] = event
            self._rotate_if_needed()
            if self.retention_events is not None and len(self._events) > self.retention_events: self.compact(self.retention_events)
            return event
    def migrate(self, migrator: Callable[[dict[str, Any]], dict[str, Any]]) -> int:
        with self._lock:
            changed = 0
            for index, event in enumerate(self._events):
                data = migrator(event.__dict__.copy())
                if data != event.__dict__: self._events[index] = Event(**data); changed += 1
            return changed
    def compact(self, keep_last: int = 1000) -> int:
        if keep_last < 0: raise ValueError("keep_last must be non-negative")
        with self._lock:
            removed = max(0, len(self._events) - keep_last)
            if removed and self.path:
                selected = self._events[-keep_last:] if keep_last else []
                rebuilt = []; previous = "GENESIS"
                for index, original in enumerate(selected):
                    data = {**original.__dict__, "sequence": index, "previous_hash": previous}
                    data["hash"] = _digest(data); rebuilt.append(Event(**data)); previous = data["hash"]
                self._events = rebuilt; self._rewrite(); self._load()
            return removed
    def _rewrite(self) -> None:
        tmp = self.path.with_suffix(self.path.suffix + ".compact"); tmp.write_text("\n".join(json.dumps(e.__dict__, ensure_ascii=False, sort_keys=True) for e in self._events) + "\n", encoding="utf-8"); os.replace(tmp, self.path)
    def all(self) -> tuple[Event, ...]:
        with self._lock: return tuple(self._events)
    def replay(self, run_id: str | None = None, task_id: str | None = None) -> tuple[Event, ...]: return tuple(e for e in self.all() if (run_id is None or e.run_id == run_id) and (task_id is None or e.task_id == task_id))
    def stream_sequence(self, run_id: str, task_id: str) -> int: return self._stream_sequences.get((run_id, task_id), 0)
    def reconstruct(self, run_id: str) -> dict[str, Any]:
        state = {"run_id": run_id, "status": "created", "approvals": {}, "steps": {}}
        for event in self.replay(run_id=run_id):
            state["last_event"] = event.type; payload = event.payload
            if event.type == EventType.APPROVAL_REQUESTED.value: state["approvals"][payload.get("approval_id", "")] = "PENDING"
            elif event.type == EventType.APPROVAL_GRANTED.value: state["approvals"][payload.get("approval_id", "")] = "APPROVED"
            elif event.type == EventType.APPROVAL_DENIED.value: state["approvals"][payload.get("approval_id", "")] = "DENIED"
            elif event.type in (EventType.DELIVERED.value, EventType.AGENT_COMPLETED.value): state["status"] = "completed" if payload.get("success", True) else "failed"
            elif event.type == EventType.RETRY.value: state["status"] = "retrying"
        return state
    def count(self) -> int: return len(self._events)
    def verify_integrity(self) -> bool:
        previous = "GENESIS"
        for index, event in enumerate(self._events):
            data = event.__dict__.copy()
            if event.sequence != index or event.previous_hash != previous or event.hash != _digest(data): return False
            previous = event.hash
        return True

    def segments(self) -> tuple[Path, ...]:
        if not self.path: return ()
        return tuple(sorted(self.path.parent.glob(self.path.name + ".*")))

def datetime_stamp() -> str:
    return now_iso().replace(":", "").replace("+", "_").replace(".", "")
