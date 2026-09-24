import tempfile
import threading
import unittest
from pathlib import Path
from brain_runtime.apis import ApiCatalogEntry, DynamicApiCatalog
from brain_runtime.models import PolicyContext
from brain_runtime.policy import PolicyBroker
from brain_runtime.sandbox import SandboxExecutor, SandboxJob
from brain_runtime.workflows import WorkflowEngine, WorkflowManifest, WorkflowNode

class SecurityE2ETests(unittest.TestCase):
    def test_cancelled_process_is_killed(self):
        with tempfile.TemporaryDirectory() as directory:
            event = threading.Event(); event.set()
            result = SandboxExecutor().execute(SandboxJob("j", "r", "s", ("python3", "-c", "import time; time.sleep(10)"), directory, cancel_event=event))
            self.assertEqual(result.status, "CANCELLED")

    def test_workflow_capabilities_are_policy_checked(self):
        broker = PolicyBroker(["read"], {"workflow": ["read"]})
        engine = WorkflowEngine(policy=broker)
        manifest = WorkflowManifest("wf", "1", (WorkflowNode("a", "write"),))
        with self.assertRaises(PermissionError): engine.run(manifest, "r", "k", lambda _: True)

    def test_quota_is_not_erased_by_health_record(self):
        entry = ApiCatalogEntry("p", "m", ("x",), "g", quota_remaining=3)
        catalog = DynamicApiCatalog([entry]); catalog.record("p", "m", True, 10)
        self.assertEqual(entry.quota_remaining, 3)

if __name__ == "__main__": unittest.main()
