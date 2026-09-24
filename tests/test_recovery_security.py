import tempfile
import threading
import unittest
from pathlib import Path
from brain_runtime.approval import ApprovalStore
from brain_runtime.credentials import CredentialRef, CredentialVault
from brain_runtime.events import EventStore
from brain_runtime.models import ApprovalRequired, Decision, EventType, PolicyContext
from brain_runtime.policy import PolicyBroker
from brain_runtime.recovery import StateReconstructor

class RecoverySecurityTests(unittest.TestCase):
    def test_approval_is_single_use_and_expires(self):
        broker = PolicyBroker(["write"], {"agent": ["write"]})
        decision = broker.authorize("agent", "write", "x", PolicyContext("r", "t", "agent", approval=ApprovalRequired.USER))
        store = ApprovalStore(); request = store.request(decision); approved = store.decide(request.approval_id, "user", True)
        self.assertEqual(approved.status, "APPROVED")
        with self.assertRaises(ValueError): store.decide(request.approval_id, "user", True)

    def test_credentials_are_references_only(self):
        vault = CredentialVault({"SERVICE_KEY": "secret"})
        self.assertEqual(vault.resolve(CredentialRef("SERVICE_KEY")), "secret")
        with self.assertRaises(ValueError): CredentialRef("bad-name!")
        self.assertEqual(vault.sanitize({"api_key": "secret"})["api_key"], "[CREDENTIAL_REF]")

    def test_replay_reconstructs_delivery_state(self):
        events = EventStore(); events.append("r", "s", "t", EventType.AGENT_COMPLETED, {"request_id": "q", "success": True}); events.append("r", "s", "t", EventType.DELIVERED, {"request_id": "q"})
        state = StateReconstructor(events).reconstruct("r")
        self.assertTrue(state.delivered); self.assertEqual(state.status, "DELIVERED")

    def test_event_store_threaded_append_has_unique_sequences(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "events.jsonl"; store = EventStore(path)
            threads = [threading.Thread(target=lambda i=i: store.append("r", "s", "t", EventType.TASK_CREATED, {"i": i})) for i in range(8)]
            for thread in threads: thread.start()
            for thread in threads: thread.join()
            restored = EventStore(path); self.assertEqual(restored.count(), 8); self.assertTrue(restored.verify_integrity())

if __name__ == "__main__": unittest.main()
