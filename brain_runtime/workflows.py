from __future__ import annotations
import json, os, threading, time
from datetime import datetime, timedelta, timezone
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable
from .models import PolicyContext, Decision
from .policy import PolicyBroker
from .host_controls import DistributedLeaseStore, Lease

@dataclass(frozen=True)
class WorkflowNode:
    node_id: str; capability: str; next_nodes: tuple[str, ...] = (); retry_limit: int = 1; dependencies: tuple[str, ...] = ()
    input_schema: tuple[str, ...] = (); output_schema: tuple[str, ...] = (); compensatable: bool = False

@dataclass(frozen=True)
class WorkflowManifest:
    workflow_id: str; workflow_version: str; nodes: tuple[WorkflowNode, ...]; enabled: bool = True
    required_capabilities: tuple[str, ...] = (); max_runtime_seconds: int = 3600

class WorkflowEngine:
    TERMINAL = {"completed", "failed", "cancelled", "timed_out"}
    def __init__(self, state_path: str | Path | None = None, policy: PolicyBroker | None = None, actor: str = "workflow", lease_store: DistributedLeaseStore | None = None):
        self.state_path = Path(state_path) if state_path else None; self._lock = threading.RLock(); self._runs: dict[str, dict] = {}; self.policy, self.actor, self.lease_store = policy, actor, lease_store
        if self.state_path and self.state_path.exists():
            try: self._runs = json.loads(self.state_path.read_text(encoding="utf-8"))
            except json.JSONDecodeError as exc: raise ValueError("workflow state is corrupted") from exc
    def _save(self) -> None:
        if not self.state_path: return
        self.state_path.parent.mkdir(parents=True, exist_ok=True); tmp = self.state_path.with_suffix(self.state_path.suffix + ".tmp"); tmp.write_text(json.dumps(self._runs, sort_keys=True), encoding="utf-8"); os.replace(tmp, self.state_path)
    @staticmethod
    def _validate(manifest: WorkflowManifest) -> dict[str, WorkflowNode]:
        nodes = {n.node_id: n for n in manifest.nodes}
        if len(nodes) != len(manifest.nodes) or any(n.retry_limit < 0 for n in manifest.nodes): raise ValueError("invalid workflow nodes")
        for node in manifest.nodes:
            if any(dep not in nodes for dep in (*node.dependencies, *node.next_nodes)): raise ValueError("workflow dependency references unknown node")
        visiting, visited = set(), set()
        def visit(node_id: str):
            if node_id in visiting: raise ValueError("workflow graph contains a cycle")
            if node_id in visited: return
            visiting.add(node_id)
            for dep in nodes[node_id].dependencies: visit(dep)
            visiting.remove(node_id); visited.add(node_id)
        for node in nodes: visit(node)
        return nodes
    @staticmethod
    def _result_payload(result: Any) -> dict[str, Any]:
        if isinstance(result, dict): return result
        if result is True: return {"success": True}
        return {"success": bool(result)}
    def _write_state(self, key: str, state: dict) -> None:
        previous = self._runs.get(key); state = dict(state); state["updated_at"] = datetime.now(timezone.utc).isoformat(); state["transitions"] = list(previous.get("transitions", [])) if previous else []; state["events"] = list(previous.get("events", [])) if previous else []
        if not previous or previous.get("status") != state.get("status"):
            transition = {"from": previous.get("status") if previous else None, "to": state.get("status"), "at": state["updated_at"], "node": state.get("current_node")}
            state["transitions"].append(transition); state["events"].append({"type": "WorkflowTransition", **transition})
        self._runs[key] = state; self._save()
    def renew_lease(self, idempotency_key: str, owner: str, lease_seconds: int = 300) -> dict:
        if lease_seconds <= 0: raise ValueError("lease must be positive")
        with self._lock:
            state = self._runs.get(idempotency_key)
            if not state: raise KeyError(idempotency_key)
            if state.get("status") in self.TERMINAL: raise RuntimeError("workflow is terminal")
            if state.get("owner") != owner: raise PermissionError("lease owner mismatch")
            if self.lease_store and state.get("fencing_token"):
                self.lease_store.renew(Lease(idempotency_key, owner, state["fencing_token"], state["lease_until"]), lease_seconds)
            state = dict(state); state["lease_until"] = (datetime.now(timezone.utc) + timedelta(seconds=lease_seconds)).isoformat(); state["updated_at"] = datetime.now(timezone.utc).isoformat(); self._runs[idempotency_key] = state; self._save(); return state
    def cancel(self, idempotency_key: str, owner: str) -> dict:
        with self._lock:
            state = self._runs.get(idempotency_key)
            if not state: raise KeyError(idempotency_key)
            if state.get("owner") not in (None, owner): raise PermissionError("lease owner mismatch")
            if state.get("status") in self.TERMINAL: return state
            state = dict(state); state["status"] = "cancelled"; state["error"] = "cancelled by owner"; self._write_state(idempotency_key, state); return self._runs[idempotency_key]
    def run(self, manifest: WorkflowManifest, run_id: str, idempotency_key: str, handler: Callable[[WorkflowNode], Any], owner: str = "local", lease_seconds: int = 300, cancel_event: object | None = None, timeout_seconds: int | None = None, backoff_seconds: float = 0.0, compensator: Callable[[WorkflowNode, dict[str, Any]], Any] | None = None, input_validator: Callable[[WorkflowNode, dict[str, Any]], bool] | None = None, output_validator: Callable[[WorkflowNode, dict[str, Any]], bool] | None = None) -> dict:
        with self._lock:
            if not manifest.enabled: raise PermissionError("workflow desabilitado")
            nodes = self._validate(manifest); existing = self._runs.get(idempotency_key)
            if existing and existing["status"] in self.TERMINAL: return existing
            if lease_seconds <= 0: raise ValueError("lease must be positive")
            now = datetime.now(timezone.utc); distributed_lease = None
            if self.lease_store:
                distributed_lease = self.lease_store.acquire(idempotency_key, owner, lease_seconds)
            if existing and existing.get("status") in {"running", "paused", "compensating"} and existing.get("owner") != owner and existing.get("lease_until", "") > now.isoformat(): raise RuntimeError("workflow run is leased by another owner")
            if self.policy:
                capabilities = set(manifest.required_capabilities) | {node.capability for node in manifest.nodes}
                for capability in capabilities:
                    decision = self.policy.authorize(self.actor, capability, manifest.workflow_id, PolicyContext(run_id, manifest.workflow_id, self.actor))
                    if decision.decision is not Decision.ALLOW: raise PermissionError(f"workflow policy denied '{capability}': {decision.reason}")
            started_at = existing.get("started_at", now.isoformat()) if existing else now.isoformat(); completed = set(existing.get("completed", [])) if existing else set(); attempts = existing.get("attempts", {}) if existing else {}; outputs = existing.get("outputs", {}) if existing else {}; input_data = existing.get("outputs", {}) if existing else {}; runtime_limit = timeout_seconds if timeout_seconds is not None else manifest.max_runtime_seconds
            def state(status: str, current: str | None = None, error: str | None = None, pause_payload: dict | None = None) -> dict:
                base = {"run_id": run_id, "workflow_id": manifest.workflow_id, "workflow_version": manifest.workflow_version, "status": status, "owner": owner, "lease_until": (datetime.now(timezone.utc) + timedelta(seconds=lease_seconds)).isoformat(), "started_at": started_at, "current_node": current, "completed": sorted(completed), "attempts": attempts, "outputs": outputs, "events": list(existing.get("events", [])) if existing else []}
                if distributed_lease: base["fencing_token"] = distributed_lease.fencing_token
                if error: base["error"] = error
                if pause_payload is not None: base["pause_payload"] = pause_payload
                return base
            try:
                for node in manifest.nodes:
                    if node.node_id in completed: continue
                    if any(dep not in completed for dep in node.dependencies): result = state("blocked", node.node_id, "node dependencies are incomplete"); self._write_state(idempotency_key, result); return result
                    if cancel_event is not None and getattr(cancel_event, "is_set", lambda: False)(): result = state("cancelled", node.node_id, "cancelled by caller"); self._write_state(idempotency_key, result); return self._compensate(result, nodes, outputs, completed, compensator, idempotency_key)
                    if (datetime.now(timezone.utc) - datetime.fromisoformat(started_at)).total_seconds() > runtime_limit: result = state("timed_out", node.node_id, "workflow runtime exceeded"); self._write_state(idempotency_key, result); return self._compensate(result, nodes, outputs, completed, compensator, idempotency_key)
                    if node.input_schema and not all(key in input_data for key in node.input_schema): result = state("failed", node.node_id, "node input validation failed"); self._write_state(idempotency_key, result); return result
                    if input_validator and not input_validator(node, input_data): result = state("failed", node.node_id, "node input validation failed"); self._write_state(idempotency_key, result); return result
                    success = False; attempt = attempts.get(node.node_id, 0); self._write_state(idempotency_key, state("running", node.node_id))
                    while attempt <= node.retry_limit:
                        if cancel_event is not None and getattr(cancel_event, "is_set", lambda: False)(): result = state("cancelled", node.node_id, "cancelled during node execution"); self._write_state(idempotency_key, result); return self._compensate(result, nodes, outputs, completed, compensator, idempotency_key)
                        attempt += 1; attempts[node.node_id] = attempt; self._write_state(idempotency_key, state("running", node.node_id))
                        try: payload = self._result_payload(handler(node))
                        except Exception as exc: payload = {"success": False, "error": str(exc)}
                        if payload.get("status") == "PAUSED":
                            # A human approval is a suspension, not a failed
                            # attempt: do not consume retry budget or undo
                            # completed nodes. The next run with the same key
                            # resumes this node from its persisted checkpoint.
                            attempts[node.node_id] = max(0, attempt - 1)
                            result = state("paused", node.node_id, payload.get("error", "workflow paused"), payload)
                            self._write_state(idempotency_key, result)
                            return result
                        if cancel_event is not None and getattr(cancel_event, "is_set", lambda: False)(): result = state("cancelled", node.node_id, "cancelled during node execution"); self._write_state(idempotency_key, result); return self._compensate(result, nodes, outputs, completed, compensator, idempotency_key)
                        valid_output = not node.output_schema or all(key in payload for key in node.output_schema)
                        if output_validator: valid_output = valid_output and output_validator(node, payload)
                        if payload.get("success", False) and valid_output: success = True; completed.add(node.node_id); outputs[node.node_id] = payload; input_data = {**input_data, **payload}; break
                        if attempt <= node.retry_limit and backoff_seconds > 0: time.sleep(min(backoff_seconds * (2 ** (attempt - 1)), 30.0))
                    if not success: result = state("failed", node.node_id, "node execution or output validation failed"); self._write_state(idempotency_key, result); return self._compensate(result, nodes, outputs, completed, compensator, idempotency_key)
                    self._write_state(idempotency_key, state("running", None))
                result = state("completed"); result.pop("owner", None); result.pop("lease_until", None); self._write_state(idempotency_key, result); return result
            finally:
                if self.lease_store and distributed_lease and (self._runs.get(idempotency_key, {}).get("status") in self.TERMINAL or self._runs.get(idempotency_key, {}).get("status") in {"completed", "paused"}): self.lease_store.release(distributed_lease)
    def _compensate(self, result: dict, nodes: dict[str, WorkflowNode], outputs: dict, completed: set[str], compensator: Callable | None, key: str) -> dict:
        if compensator and completed:
            result = dict(result); result["status"] = "compensating"; self._write_state(key, result)
            for node_id in reversed([node_id for node_id in nodes if node_id in completed]):
                node = nodes[node_id]
                if node.compensatable: compensator(node, outputs.get(node_id, {}))
            result["status"] = "cancelled" if result.get("error", "").startswith("cancel") else result.get("status", "failed"); self._write_state(key, result)
        return self._runs.get(key, result)
