import tempfile
import time
import unittest
from pathlib import Path
from brain_runtime.events import EventStore
from brain_runtime.host_controls import DistributedLeaseStore, LeaseHeartbeat
from brain_runtime.modes import RuntimeMode, validate_runtime_components
from brain_runtime.models import EventType
from brain_runtime.skills import SkillManifest, SkillRegistry
from brain_runtime.runtime import RuntimeCoordinator
from brain_runtime.readiness import ReadinessGate

class RemainingHardeningTests(unittest.TestCase):
    def test_modes_are_fail_closed(self):
        with self.assertRaises(RuntimeError): validate_runtime_components(RuntimeMode.STRICT, has_qa=False, has_observability=True, has_dispatcher=True, has_policy=True)
        self.assertEqual(validate_runtime_components(RuntimeMode.OFFLINE, has_qa=True, has_observability=True, has_dispatcher=True, has_policy=True).mode, RuntimeMode.OFFLINE)

    def test_non_development_modes_require_readiness_and_cannot_disable_it(self):
        with self.assertRaises(ValueError): RuntimeCoordinator(object(), object(), EventStore(), mode=RuntimeMode.PRODUCTION)
        with self.assertRaises(ValueError): RuntimeCoordinator(object(), object(), EventStore(), readiness_gate=ReadinessGate(), enforce_readiness=False, mode=RuntimeMode.STRICT)
        runtime = RuntimeCoordinator(object(), object(), EventStore(), readiness_gate=ReadinessGate(), mode=RuntimeMode.PRODUCTION)
        self.assertTrue(runtime.enforce_readiness)

    def test_event_retention_and_segments(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "events.jsonl"; store = EventStore(path, retention_events=2)
            for index in range(4): store.append("r", "s", "t", EventType.TASK_CREATED, {"index": index})
            self.assertLessEqual(store.count(), 2); self.assertTrue(store.verify_integrity())

    def test_lease_heartbeat_and_fencing(self):
        with tempfile.TemporaryDirectory() as directory:
            store = DistributedLeaseStore(Path(directory) / "lease.db"); lease = store.acquire("k", "o", 1); heartbeat = LeaseHeartbeat(store, lease, 1); heartbeat.start(); time.sleep(.05); store.assert_current(heartbeat.lease); heartbeat.stop(); store.release(heartbeat.lease)

    def test_versioned_skill_revocation_survives_restart(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "quarantine.jsonl"; registry = SkillRegistry(quarantine_path=path); registry.revoke("s", "bad", version="1")
            restored = SkillRegistry(quarantine_path=path)
            with self.assertRaises(PermissionError): restored.register(SkillManifest("s", "1", "x", ("x",), body_path="body.md", trust_level="verified"))

if __name__ == "__main__": unittest.main()
