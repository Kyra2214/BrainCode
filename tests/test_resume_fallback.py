import tempfile
import unittest
from pathlib import Path
from brain_runtime.approval import ApprovalStore
from brain_runtime.fallback import AuthorizedFallback
from brain_runtime.models import ApprovalRequired, ExecutionResult, PolicyContext
from brain_runtime.policy import PolicyBroker

class ResumeFallbackTests(unittest.TestCase):
    def test_approval_restore_is_append_safe_and_pending(self):
        with tempfile.TemporaryDirectory() as directory:
            store = ApprovalStore(Path(directory) / "approvals.db"); payload = {"approval_id": "a", "decision_id": "d", "run_id": "r", "task_id": "t", "actor": "a", "capability": "x", "required": "USER", "resource": "p", "created_at": "2026-01-01T00:00:00+00:00", "expires_at": "2999-01-01T00:00:00+00:00"}
            first = store.restore(payload); second = store.restore(payload)
            self.assertEqual(first.approval_id, second.approval_id); self.assertEqual(len(store.pending()), 1)

    def test_fallback_reauthorizes_each_provider_and_is_idempotent(self):
        policy = PolicyBroker(["x"], {"agent": ["x"]}); calls = []
        fallback = AuthorizedFallback(policy, "x", "agent", "run", "task")
        def call(provider, decision_id): calls.append((provider, decision_id)); return ExecutionResult("req", provider == "good", error=None if provider == "good" else "failed")
        result = fallback.execute(("bad", "good"), call)
        self.assertTrue(result.success); self.assertEqual([item[0] for item in calls], ["bad", "good"]); self.assertEqual(len(fallback.attempts), 2)
        fallback.execute(("good",), call); self.assertEqual(len(calls), 2)

if __name__ == "__main__": unittest.main()
