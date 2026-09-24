import tempfile
import unittest
from pathlib import Path

from brain_runtime.events import EventStore
from brain_runtime.models import ApprovalRequired, Decision, EventType, PolicyContext
from brain_runtime.policy import PolicyBroker


class PolicyEventTests(unittest.TestCase):
    def test_policy_is_deny_by_default_and_allows_registered_actor(self):
        context = PolicyContext("run-1", "task-1", "agent", risk_class="LOW")
        broker = PolicyBroker(["read"], {"agent": ["read"]})
        self.assertEqual(broker.authorize("agent", "write", "/tmp", context).decision, Decision.DENY)
        self.assertEqual(broker.authorize("agent", "read", "/tmp", context).decision, Decision.ALLOW)

    def test_policy_asks_for_approval(self):
        context = PolicyContext("run-1", "task-1", "agent", approval=ApprovalRequired.USER)
        decision = PolicyBroker(["read"], {"agent": ["read"]}).authorize("agent", "read", "/tmp", context)
        self.assertEqual(decision.decision, Decision.ASK)

    def test_event_store_is_durable_replayable_and_redacts_secrets(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "events.jsonl"
            store = EventStore(path)
            store.append("run", "session", "task", EventType.POLICY_CHECKED, {"token": "do-not-persist", "ok": True})
            restored = EventStore(path)
            events = restored.replay(run_id="run")
            self.assertEqual(len(events), 1)
            self.assertEqual(events[0].payload["token"], "[REDACTED]")
            self.assertEqual(events[0].sequence, 0)


if __name__ == "__main__":
    unittest.main()
