from __future__ import annotations
import json, sqlite3, threading
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from .models import ApprovalRequired, Decision, PolicyDecision, new_id

@dataclass(frozen=True)
class ApprovalRequest:
    approval_id: str
    decision_id: str
    run_id: str
    task_id: str
    actor: str
    capability: str
    required: ApprovalRequired
    resource: str
    created_at: str
    expires_at: str
    status: str = "PENDING"
    approver: str = ""

class ApprovalStore:
    def __init__(self, path: str | Path = ":memory:"):
        self._lock = threading.RLock()
        self._db = sqlite3.connect(str(path), check_same_thread=False)
        self._db.execute("CREATE TABLE IF NOT EXISTS approvals (id TEXT PRIMARY KEY, payload TEXT NOT NULL)")
        self._db.commit()

    def request(self, decision: PolicyDecision) -> ApprovalRequest:
        if decision.decision is not Decision.ASK:
            raise ValueError("only ASK decisions can request approval")
        item = ApprovalRequest(new_id("approval"), decision.decision_id, decision.run_id, decision.task_id,
            decision.actor, decision.capability, decision.approval_required, decision.resource,
            datetime.now(timezone.utc).isoformat(), decision.expires_at)
        with self._lock:
            self._db.execute("INSERT INTO approvals VALUES (?, ?)", (item.approval_id, json.dumps(item.__dict__)))
            self._db.commit()
        return item

    def restore(self, payload: dict) -> ApprovalRequest:
        """Rehydrates a pending request from an append-only approval event after restart."""
        required = ("approval_id", "decision_id", "run_id", "task_id", "actor", "capability", "resource", "created_at", "expires_at")
        if any(key not in payload for key in required): raise ValueError("approval event is not rehydratable")
        item = ApprovalRequest(payload["approval_id"], payload["decision_id"], payload["run_id"], payload["task_id"], payload["actor"], payload["capability"], ApprovalRequired(payload.get("required", "USER")), payload.get("resource", ""), payload["created_at"], payload["expires_at"], payload.get("status", "PENDING"), payload.get("approver", ""))
        with self._lock:
            existing = self._db.execute("SELECT payload FROM approvals WHERE id=?", (item.approval_id,)).fetchone()
            if existing: return self.get(item.approval_id)
            self._db.execute("INSERT INTO approvals VALUES (?, ?)", (item.approval_id, json.dumps(item.__dict__))); self._db.commit()
        return item

    def pending(self) -> tuple[ApprovalRequest, ...]:
        rows = self._db.execute("SELECT payload FROM approvals").fetchall(); return tuple(ApprovalRequest(**json.loads(row[0])) for row in rows if json.loads(row[0]).get("status") == "PENDING")

    def get(self, approval_id: str) -> ApprovalRequest:
        row = self._db.execute("SELECT payload FROM approvals WHERE id=?", (approval_id,)).fetchone()
        if not row:
            raise KeyError(approval_id)
        data = json.loads(row[0]); data.setdefault("resource", "")
        return ApprovalRequest(**data)

    def decide(self, approval_id: str, approver: str, approve: bool, *, run_id: str | None = None,
               task_id: str | None = None, capability: str | None = None, resource: str | None = None) -> ApprovalRequest:
        with self._lock:
            item = self.get(approval_id)
            if item.status != "PENDING":
                raise ValueError("approval is already decided")
            if run_id is not None and item.run_id != run_id:
                raise PermissionError("approval does not belong to run")
            if task_id is not None and item.task_id != task_id:
                raise PermissionError("approval does not belong to task")
            if capability is not None and item.capability != capability:
                raise PermissionError("approval capability mismatch")
            if resource is not None and item.resource != resource:
                raise PermissionError("approval resource mismatch")
            if datetime.now(timezone.utc) >= datetime.fromisoformat(item.expires_at):
                raise TimeoutError("approval expired")
            updated = ApprovalRequest(**{**item.__dict__, "status": "APPROVED" if approve else "DENIED", "approver": approver})
            self._db.execute("UPDATE approvals SET payload=? WHERE id=?", (json.dumps(updated.__dict__), approval_id))
            self._db.commit()
            return updated
