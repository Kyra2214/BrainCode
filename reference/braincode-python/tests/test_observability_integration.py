import json
import unittest
from brain_runtime.delivery import DeliveryPipeline, QAGate
from brain_runtime.events import EventStore
from brain_runtime.models import ExecutionResult
from brain_runtime.observability import Observability, TraceContext
from brain_runtime.pipeline import BrainPipeline, DefaultPromptBuilder, KeywordSecretary, StaticRouter
from brain_runtime.policy import PolicyBroker

class Dispatcher:
    def dispatch(self, request): return ExecutionResult(request.request_id, True, {"ok": True}, evidence=("validation",))

class ObservabilityIntegrationTests(unittest.TestCase):
    def test_export_contains_spans_metrics_and_alerts(self):
        telemetry = Observability(); context = TraceContext("trace", "run", "session", "task")
        span = telemetry.start("policy", context); telemetry.finish(span); telemetry.increment("retry", 2); telemetry.gauge("latency_ms", 10); telemetry.alert("policy_denied", "denied", context)
        payload = json.loads(telemetry.export_json())
        self.assertEqual(payload["spans"][0]["context"]["trace_id"], "trace"); self.assertEqual(payload["counters"]["retry"], 2); self.assertEqual(payload["alerts"][0]["name"], "policy_denied")

    def test_pipeline_records_execution_span_and_policy_alert(self):
        telemetry = Observability(); events = EventStore()
        pipeline = BrainPipeline(KeywordSecretary({"research": ("pesquise",)}), StaticRouter({"default": "local"}), DefaultPromptBuilder(), PolicyBroker(), events, Dispatcher(), observability=telemetry)
        result = pipeline.run("Pesquise algo", "session")[0]
        self.assertEqual(result.status, "DENIED"); self.assertTrue(any(span.name == "pipeline.execute" for span in telemetry.spans())); self.assertEqual(telemetry.alerts()[0]["name"], "policy_denied")

    def test_delivery_records_metrics(self):
        telemetry = Observability(); context = TraceContext("trace", "run", "session", "task"); pipeline = DeliveryPipeline(QAGate(), telemetry)
        delivered = pipeline.deliver(ExecutionResult("r", True, {"answer": "ok"}, evidence=("qa",)), "ok", context)
        self.assertTrue(delivered.success); self.assertEqual(telemetry.counters()["delivery.delivered"], 1); self.assertEqual(telemetry.spans()[0].context.trace_id, "trace")

if __name__ == "__main__": unittest.main()
