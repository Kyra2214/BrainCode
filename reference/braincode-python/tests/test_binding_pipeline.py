import unittest
from brain_runtime.binding import bind_execution
from brain_runtime.delivery import QAGate
from brain_runtime.events import EventStore
from brain_runtime.models import ExecutionResult
from brain_runtime.pipeline import BrainPipeline, DefaultPromptBuilder, KeywordSecretary, StaticRouter
from brain_runtime.policy import PolicyBroker

class Dispatcher:
    def dispatch(self, request): return ExecutionResult(request.request_id, True, {"answer": "ok"}, evidence=("dispatch",))

class BindingPipelineTests(unittest.TestCase):
    def test_binding_digest_detects_mutation(self):
        binding = bind_execution(run_id="r", task_id="t", step_id="s", capability="x", provider="p", policy_decision_id="d")
        self.assertTrue(binding.verify(binding.digest())); self.assertFalse(binding.verify("tampered"))

    def test_pipeline_persists_binding_digest(self):
        events = EventStore(); pipeline = BrainPipeline(KeywordSecretary({"x": ("x",)}), StaticRouter({"default": "p"}), DefaultPromptBuilder(), PolicyBroker(["x"], {"brain": ["x"]}), events, Dispatcher())
        result = pipeline.run("x", "session")[0]
        self.assertTrue(result.success); dispatched = next(event for event in events.all() if event.type == "AgentDispatched"); self.assertTrue(dispatched.payload["binding_digest"])

    def test_pipeline_can_apply_qa_gate_before_delivery(self):
        events = EventStore(); pipeline = BrainPipeline(KeywordSecretary({"x": ("x",)}), StaticRouter({"default": "p"}), DefaultPromptBuilder(), PolicyBroker(["x"], {"brain": ["x"]}), events, Dispatcher(), qa_gate=QAGate())
        result = pipeline.run("x", "session")[0]
        self.assertTrue(result.success)

if __name__ == "__main__": unittest.main()
