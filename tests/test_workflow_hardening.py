import threading
import unittest
from brain_runtime.workflows import WorkflowEngine, WorkflowManifest, WorkflowNode

class WorkflowHardeningTests(unittest.TestCase):
    def test_cancel_propagates_and_compensates_completed_nodes(self):
        cancel = threading.Event(); compensated = []
        engine = WorkflowEngine(); calls = []
        def handler(node):
            calls.append(node.node_id)
            if node.node_id == "b": cancel.set()
            return True
        def compensate(node, output): compensated.append(node.node_id)
        manifest = WorkflowManifest("wf", "1", (
            WorkflowNode("a", "a", next_nodes=("b",), compensatable=True),
            WorkflowNode("b", "b", dependencies=("a",), compensatable=True),))
        state = engine.run(manifest, "r", "k", handler, cancel_event=cancel, compensator=compensate)
        self.assertEqual(state["status"], "cancelled")
        self.assertEqual(compensated, ["a"])

    def test_output_validation_retries_and_fails(self):
        engine = WorkflowEngine(); calls = {"a": 0}
        def handler(node): calls["a"] += 1; return {"success": True}
        manifest = WorkflowManifest("wf", "1", (WorkflowNode("a", "a", retry_limit=1, output_schema=("value",)),))
        state = engine.run(manifest, "r", "k", handler)
        self.assertEqual(state["status"], "failed"); self.assertEqual(calls["a"], 2)

    def test_input_validation_blocks_node(self):
        engine = WorkflowEngine(); manifest = WorkflowManifest("wf", "1", (WorkflowNode("a", "a", input_schema=("required",)),))
        state = engine.run(manifest, "r", "k", lambda _: True)
        self.assertEqual(state["status"], "failed")
        self.assertIn("input validation", state["error"])

if __name__ == "__main__": unittest.main()
