import tempfile
import unittest
from pathlib import Path
from brain_runtime.workflows import WorkflowEngine, WorkflowManifest, WorkflowNode


class WorkflowTests(unittest.TestCase):
    def test_retry_and_idempotency(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "workflow.json"
            engine = WorkflowEngine(path)
            calls = {"a": 0}
            def handler(node):
                calls[node.node_id] += 1
                return calls[node.node_id] >= 2
            manifest = WorkflowManifest("wf", "1", (WorkflowNode("a", "test", retry_limit=2),))
            state = engine.run(manifest, "run-1", "idem-1", handler)
            self.assertEqual(state["status"], "completed")
            self.assertEqual(calls["a"], 2)
            restored = WorkflowEngine(path)
            restored.run(manifest, "run-2", "idem-1", lambda _: False)
            self.assertEqual(calls["a"], 2)


if __name__ == "__main__":
    unittest.main()
