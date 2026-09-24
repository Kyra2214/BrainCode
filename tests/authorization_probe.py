from brain_runtime.authorization import ExecutionAuthorizationError
from brain_runtime.events import EventStore
from brain_runtime.models import ExecutionResult
from brain_runtime.pipeline import BrainPipeline, DefaultPromptBuilder, KeywordSecretary, StaticRouter
from brain_runtime.policy import PolicyBroker


class Dispatcher:
    def dispatch(self, request):
        return ExecutionResult(request.request_id, True, {"answer": "ok"})


def make_pipeline():
    return BrainPipeline(
        KeywordSecretary({"x": ("x",)}),
        StaticRouter({"default": "local"}),
        DefaultPromptBuilder(),
        PolicyBroker(["x"], {"brain": ["x"]}),
        EventStore(),
        Dispatcher(),
    )


pipeline = make_pipeline()
try:
    pipeline.run("x", "session")
except ExecutionAuthorizationError:
    print("DIRECT_RUN_BLOCKED")
else:
    raise AssertionError("direct run unexpectedly succeeded")
