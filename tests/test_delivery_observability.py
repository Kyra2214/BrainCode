import tempfile
import unittest
from pathlib import Path
from brain_runtime.configuration import assert_isolated_path, validate_configuration
from brain_runtime.contracts import ContractError
from brain_runtime.delivery import DeliveryPipeline, QAGate
from brain_runtime.learning import FeedbackLoop, LearningRecord, LearningStore
from brain_runtime.models import ExecutionResult
from brain_runtime.observability import Observability, TraceContext

class DeliveryObservabilityTests(unittest.TestCase):
    def test_trace_and_metrics_keep_correlation(self):
        context = TraceContext("trace", "run", "session", "task")
        telemetry = Observability(); span = telemetry.start("dispatch", context); finished = telemetry.finish(span)
        self.assertEqual(finished.context.run_id, "run"); self.assertEqual(telemetry.counters()["span_finished:OK"], 1)

    def test_learning_requires_evidence_and_feedback_is_append_only(self):
        store = LearningStore(); loop = FeedbackLoop(store)
        item = LearningRecord("x", "r", "t", "problem", "strategy", ("source",), "result", .5, validation_evidence=("qa",))
        learned = loop.learn(item, True, ("event",))
        self.assertNotEqual(learned.record_id, item.record_id); self.assertEqual(store.count(), 1)
        with self.assertRaises(ValueError): store.record(LearningRecord("bad", "r", "t", "p", "s", (), "r", .5))

    def test_delivery_requires_success_and_evidence(self):
        pipeline = DeliveryPipeline(QAGate())
        failed = pipeline.deliver(ExecutionResult("r", True, {"answer": "ok"}), "ok")
        self.assertFalse(failed.success); self.assertEqual(failed.status, "VALIDATION_FAILED")
        delivered = pipeline.deliver(ExecutionResult("r", True, {"answer": "ok"}, evidence=("event-1",)), "ok")
        self.assertTrue(delivered.success); self.assertEqual(delivered.status, "DELIVERED")

    def test_configuration_is_deny_by_default_and_paths_are_isolated(self):
        config = {"policy": {"deny_by_default": True}, "execution": {}, "providers": {}, "routing": {}}
        self.assertEqual(validate_configuration(config), config)
        with self.assertRaises(ContractError): validate_configuration({**config, "secrets": {"x": "y"}})
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); self.assertEqual(assert_isolated_path(root, root), root)
            with self.assertRaises(PermissionError): assert_isolated_path(root.parent, root)

if __name__ == "__main__": unittest.main()
