from __future__ import annotations
from typing import Any
from .events import EventStore

REQUIRED = {"event_id", "run_id", "session_id", "task_id", "timestamp", "type", "version", "sequence", "payload", "previous_hash", "hash", "correlation_id"}

def golden_projection(store: EventStore) -> tuple[dict[str, Any], ...]:
    return tuple({"type": event.type, "version": event.version, "sequence": event.sequence, "run_id": event.run_id, "task_id": event.task_id, "payload": event.payload, "previous_hash": event.previous_hash, "hash": event.hash, "correlation_id": event.correlation_id} for event in store.all())

def validate_golden_stream(store: EventStore) -> None:
    if not store.verify_integrity(): raise AssertionError("event hash chain is invalid")
    previous = "GENESIS"
    for index, event in enumerate(store.all()):
        missing = REQUIRED - set(event.__dict__)
        if missing: raise AssertionError(f"event contract missing fields: {sorted(missing)}")
        if event.sequence != index or event.previous_hash != previous: raise AssertionError("event sequence or chain mismatch")
        previous = event.hash

def compare_golden(store: EventStore, expected: tuple[dict[str, Any], ...]) -> None:
    actual = golden_projection(store)
    if actual != expected: raise AssertionError(f"golden event mismatch: expected {expected!r}, got {actual!r}")
