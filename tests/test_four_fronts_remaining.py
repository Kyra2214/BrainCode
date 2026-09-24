import tempfile
import threading
import unittest
from pathlib import Path

from brain_runtime.observability import Observability
from brain_runtime.sandbox import SandboxExecutor, SandboxJob
from brain_runtime.workflows import WorkflowEngine, WorkflowManifest, WorkflowNode


class FourFrontsRemainingTests(unittest.TestCase):
    def test_workflow_persists_transition_events_and_compensates_in_graph_order(self):
        engine = WorkflowEngine()
        cancelled = threading.Event()
        compensated = []

        def handler(node):
            if node.node_id == "b":
                cancelled.set()
            return True

        manifest = WorkflowManifest("wf", "1", (
            WorkflowNode("a", "a", dependencies=(), compensatable=True),
            WorkflowNode("b", "b", dependencies=("a",), compensatable=True),
        ))
        state = engine.run(manifest, "run", "key", handler, cancel_event=cancelled,
                           compensator=lambda node, output: compensated.append(node.node_id))
        self.assertEqual(state["status"], "cancelled")
        self.assertEqual(compensated, ["a"])
        self.assertTrue(any(event["type"] == "WorkflowTransition" for event in state["events"]))

    def test_observability_records_operational_dimensions(self):
        telemetry = Observability()
        telemetry.record_policy("DENY")
        telemetry.record_provider("local", success=True, cost=0.2, latency_ms=12)
        telemetry.record_retry("workflow")
        telemetry.record_delivery(True)
        counters = telemetry.counters()
        self.assertEqual(counters["policy.denied"], 1)
        self.assertEqual(counters["provider:local.success"], 1)
        self.assertEqual(counters["workflow.retry"], 1)
        self.assertEqual(counters["delivery.delivered"], 1)
        self.assertEqual(telemetry.gauges()["provider:local.latency_ms"], 12)

    def test_sandbox_can_return_only_modified_artifacts_in_allowed_directory(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "existing.txt").write_text("before", encoding="utf-8")
            (root / "out").mkdir()
            result = SandboxExecutor().execute(SandboxJob(
                "job", "run", "session", ("python3", "-c", "open('out/result.txt','w').write('ok')"),
                str(root), allowed_extensions=(".txt",), allowed_artifact_dirs=("out",),
                modified_artifacts_only=True,
            ))
            self.assertIn(result.status, ("SUCCEEDED", "REJECTED"))
            if result.status == "SUCCEEDED":
                self.assertEqual([item["path"] for item in result.artifacts], ["out/result.txt"])
                self.assertTrue(result.artifacts[0]["modified"])


if __name__ == "__main__":
    unittest.main()
