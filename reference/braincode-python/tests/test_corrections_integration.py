import tempfile
import threading
import unittest
from pathlib import Path
from brain_runtime.approval import ApprovalStore
from brain_runtime.events import EventStore
from brain_runtime.models import ApprovalRequired, EventType, ExecutionResult
from brain_runtime.pipeline import BrainPipeline, DefaultPromptBuilder, KeywordSecretary, StaticRouter
from brain_runtime.policy import PolicyBroker
from brain_runtime.sandbox import SandboxExecutor, SandboxJob

class FailingOnce:
    def __init__(self): self.calls = 0
    def dispatch(self, request):
        self.calls += 1
        return ExecutionResult(request.request_id, self.calls > 1, error=None if self.calls > 1 else "bad output")

class IntegrationTests(unittest.TestCase):
    def make_pipeline(self, events, dispatcher, approval_store=None, approval=False):
        return BrainPipeline(KeywordSecretary({"research": ("pesquise",)}), StaticRouter({"research": "local"}),
            DefaultPromptBuilder(), PolicyBroker(["research"], {"brain": ["research"]}), events, dispatcher,
            approval_store=approval_store, max_retries=1)

    def test_failed_validation_is_corrected_and_retried(self):
        events = EventStore(); pipeline = self.make_pipeline(events, FailingOnce())
        result = pipeline.run("Pesquise dados", "s")[0]
        self.assertTrue(result.success)
        types = [item.type for item in events.all()]
        self.assertIn(EventType.CORRECTION_REQUESTED.value, types)
        self.assertIn(EventType.RETRY.value, types)
        self.assertIn(EventType.DELIVERED.value, types)

    def test_approval_pauses_and_resumes_same_run(self):
        events = EventStore(); store = ApprovalStore(); pipeline = self.make_pipeline(events, FailingOnce(), store)
        # Force approval through the broker/context used by a dedicated policy instance.
        pipeline.policy = PolicyBroker(["research"], {"brain": ["research"]})
        original = pipeline.policy.authorize
        def ask(*args, **kwargs):
            decision = original(*args, **kwargs)
            from brain_runtime.models import PolicyDecision, Decision
            return PolicyDecision(decision.decision_id, decision.run_id, decision.task_id, decision.actor, decision.capability,
                decision.risk_class, Decision.ASK, ApprovalRequired.USER, decision.sandbox_required, decision.network_allowed,
                decision.filesystem_roots, decision.budget, decision.expires_at, "approval required", decision.resource)
        pipeline.policy.authorize = ask
        paused = pipeline.run("Pesquise dados", "s")[0]
        approval_id = paused.output["approval_id"]
        run_id = store.get(approval_id).run_id
        resumed = pipeline.resume(approval_id, "user", True)[0]
        self.assertTrue(resumed.success)
        self.assertEqual(run_id, store.get(approval_id).run_id)
        self.assertIn(EventType.APPROVAL_GRANTED.value, [e.type for e in events.all()])
        with self.assertRaises(KeyError): pipeline.resume(approval_id, "user", True)

    def test_approval_restart_preserves_same_step(self):
        class CapturingDispatcher:
            def __init__(self): self.step_ids = []
            def dispatch(self, request):
                self.step_ids.append(request.step_id)
                return ExecutionResult(request.request_id, True)

        with tempfile.TemporaryDirectory() as directory:
            events_path = Path(directory) / "events.jsonl"
            approval_path = Path(directory) / "approvals.db"
            first_events = EventStore(events_path)
            first_store = ApprovalStore(approval_path)
            first = self.make_pipeline(first_events, CapturingDispatcher(), first_store)
            original = first.policy.authorize
            def ask(*args, **kwargs):
                decision = original(*args, **kwargs)
                from brain_runtime.models import PolicyDecision, Decision
                return PolicyDecision(decision.decision_id, decision.run_id, decision.task_id, decision.actor, decision.capability,
                    decision.risk_class, Decision.ASK, ApprovalRequired.USER, decision.sandbox_required, decision.network_allowed,
                    decision.filesystem_roots, decision.budget, decision.expires_at, "approval required", decision.resource)
            first.policy.authorize = ask
            paused = first.run("Pesquise dados", "s")[0]
            approval_id = paused.output["approval_id"]
            requested = next(event for event in first_events.all() if event.type == EventType.APPROVAL_REQUESTED.value)
            step_id = requested.payload["step_id"]

            restarted_events = EventStore(events_path)
            restarted = self.make_pipeline(restarted_events, CapturingDispatcher(), ApprovalStore(approval_path))
            resumed = restarted.resume(approval_id, "user", True)[0]
            dispatched = next(event for event in restarted_events.all() if event.type == EventType.AGENT_DISPATCHED.value)
            self.assertTrue(resumed.success)
            self.assertEqual(step_id, dispatched.payload["step_id"])

    def test_event_store_recovers_trailing_partial_line(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "events.jsonl"; store = EventStore(path)
            store.append("r", "s", "t", EventType.TASK_CREATED, {"ok": True})
            with path.open("ab") as stream: stream.write(b'{"partial":')
            restored = EventStore(path)
            self.assertEqual(restored.count(), 1); self.assertTrue(restored.verify_integrity())

    def test_sandbox_rejects_shell_metacharacters(self):
        with tempfile.TemporaryDirectory() as directory:
            result = SandboxExecutor().execute(SandboxJob("j", "r", "s", ("python3", "-c", "print(1); print(2)"), directory))
            self.assertEqual(result.status, "REJECTED")

if __name__ == "__main__": unittest.main()
