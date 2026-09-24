import unittest
from brain_runtime.events import EventStore
from brain_runtime.models import ExecutionResult
from brain_runtime.pipeline import BrainPipeline, DefaultPromptBuilder, KeywordSecretary, StaticRouter
from brain_runtime.policy import PolicyBroker


class Dispatcher:
    def dispatch(self, request):
        return ExecutionResult(request.request_id, True, {"provider": request.inputs["provider"], "prompt": request.objective})


class PipelineTests(unittest.TestCase):
    def test_pipeline_emits_trace_and_dispatches(self):
        events = EventStore()
        pipeline = BrainPipeline(
            KeywordSecretary({"research": ("pesquise", "pesquisa")}),
            StaticRouter({"research": "local", "default": "local"}),
            DefaultPromptBuilder(),
            PolicyBroker(["research"], {"brain": ["research"]}),
            events,
            Dispatcher(),
        )
        results = pipeline.run("Pesquise concorrentes", "session-1")
        self.assertEqual(len(results), 1)
        self.assertTrue(results[0].success)
        self.assertIn("TaskCreated", [event.type for event in events.all()])
        self.assertIn("AgentCompleted", [event.type for event in events.all()])


if __name__ == "__main__":
    unittest.main()
