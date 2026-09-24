"""Probes de auditoria; executados pela auditoria, não são testes de aceitação."""
from __future__ import annotations
import json
import tempfile
import threading
from pathlib import Path
from brain_runtime.events import EventStore
from brain_runtime.models import ApprovalRequired, PolicyContext
from brain_runtime.policy import PolicyBroker
from brain_runtime.sandbox import QAGate, SandboxExecutor, SandboxJob
from brain_runtime.workflows import WorkflowEngine, WorkflowManifest, WorkflowNode


def policy_resource_probe():
    ctx = PolicyContext("r", "t", "a", risk_class="LOW", filesystem_roots=("/safe",), network_allowed=False)
    decision = PolicyBroker(["filesystem_write"], {"a": ["filesystem_write"]}).authorize("a", "filesystem_write", "/etc", ctx)
    assert decision.decision.value == "ALLOW", "expected finding: resource path is not checked"


def sandbox_command_probe():
    with tempfile.TemporaryDirectory() as root:
        result = SandboxExecutor().execute(SandboxJob("j", "r", "s", ("sh", "-c", "echo bypass"), root, allowed_commands=("sh",)))
        assert result.status == "SUCCEEDED", "expected finding: job controls its own allowlist"


def sandbox_python_escape_probe():
    with tempfile.TemporaryDirectory() as root:
        result = SandboxExecutor().execute(SandboxJob("j", "r", "s", ("python3", "-c", "import socket; print(socket.gethostname())"), root))
        assert result.status == "SUCCEEDED", "expected finding: python allowlist is not isolation"


def event_concurrent_instances_probe():
    with tempfile.TemporaryDirectory() as directory:
        path = Path(directory) / "events.jsonl"
        a, b = EventStore(path), EventStore(path)
        errors = []
        def write(store):
            try:
                for _ in range(50): store.append("r", "s", "t", "x", {})
            except Exception as exc: errors.append(exc)
        threads = [threading.Thread(target=write, args=(store,)) for store in (a, b)]
        [thread.start() for thread in threads]
        [thread.join() for thread in threads]
        rows = path.read_text().splitlines()
        sequences = [json.loads(row)["sequence"] for row in rows]
        assert len(rows) == 100 and len(set(sequences)) < len(sequences), "expected finding: duplicate sequences across instances"


def workflow_concurrent_probe():
    with tempfile.TemporaryDirectory() as directory:
        path = Path(directory) / "state.json"
        engine = WorkflowEngine(path)
        manifest = WorkflowManifest("wf", "1", (WorkflowNode("n", "x"),))
        calls = []
        barrier = threading.Barrier(2)
        def handler(_):
            calls.append(1); barrier.wait(); return True
        errors = []
        def run():
            try: engine.run(manifest, "r", "same", handler)
            except Exception as exc: errors.append(exc)
        threads = [threading.Thread(target=run) for _ in range(2)]
        [thread.start() for thread in threads]
        [thread.join() for thread in threads]
        assert len(calls) == 2, "expected finding: concurrent same idempotency key executes twice"


if __name__ == "__main__":
    for name, probe in sorted(globals().items()):
        if name.endswith("_probe"):
            try:
                probe(); print(f"CONFIRMED: {name}")
            except Exception as exc:
                print(f"NOT CONFIRMED: {name}: {exc}")
