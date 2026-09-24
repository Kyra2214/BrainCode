import unittest
from brain_runtime.events import EventStore
from brain_runtime.models import ExecutionResult
from brain_runtime.pipeline import BrainPipeline, DefaultPromptBuilder, KeywordSecretary, StaticRouter
from brain_runtime.policy import PolicyBroker
from brain_runtime.authorization import ExecutionAuthorization, ExecutionAuthorizationError
from brain_runtime.modes import RuntimeMode


class Dispatcher:
    def dispatch(self, request):
        return ExecutionResult(request.request_id, True, {"provider": request.inputs["provider"], "prompt": request.objective})


class PipelineTests(unittest.TestCase):
    def make_pipeline(self, mode=RuntimeMode.DEVELOPMENT):
        return BrainPipeline(
            KeywordSecretary({"research": ("pesquise", "pesquisa")}),
            StaticRouter({"research": "local", "default": "local"}),
            DefaultPromptBuilder(),
            PolicyBroker(["research"], {"brain": ["research"]}),
            EventStore(),
            Dispatcher(),
            mode=mode,
        )

    def test_real_run_requires_runtime_authorization(self):
        with self.assertRaises(ExecutionAuthorizationError): self.make_pipeline().run("Pesquise concorrentes", "session-1")

    def test_test_route_is_unavailable_outside_development(self):
        with self.assertRaises(ExecutionAuthorizationError): self.make_pipeline(RuntimeMode.STRICT).run_internal_for_tests("Pesquise concorrentes", "session-1")

    def test_consumers_cannot_issue_pipeline_authorization(self):
        pipeline = self.make_pipeline()
        with self.assertRaises(ExecutionAuthorizationError):
            ExecutionAuthorization._issue(pipeline, RuntimeMode.DEVELOPMENT)

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
        results = pipeline.run_internal_for_tests("Pesquise concorrentes", "session-1")
        self.assertEqual(len(results), 1)
        self.assertTrue(results[0].success)
        self.assertIn("TaskCreated", [event.type for event in events.all()])
        self.assertIn("AgentCompleted", [event.type for event in events.all()])


if __name__ == "__main__":
    unittest.main()
