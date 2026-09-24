import threading
import tempfile
import unittest
from pathlib import Path
from brain_runtime.delivery import DeliveryPipeline, QAGate
from brain_runtime.events import EventStore
from brain_runtime.models import ExecutionResult
from brain_runtime.pipeline import BrainPipeline, DefaultPromptBuilder, KeywordSecretary, StaticRouter
from brain_runtime.policy import PolicyBroker
from brain_runtime.runtime import FaultInjected, RuntimeCoordinator
from brain_runtime.project_intelligence import ProjectScanner
from brain_runtime.readiness import ReadinessGate
from brain_runtime.api_discovery import ApiCandidate, ApiDiscovery
from brain_runtime.apis import DynamicApiCatalog

class Dispatcher:
    def dispatch(self, request): return ExecutionResult(request.request_id, True, {"answer": "ok"}, evidence=("validation",))

class RuntimeE2ETests(unittest.TestCase):
    def make_runtime(self, fault=None):
        events = EventStore(); pipeline = BrainPipeline(KeywordSecretary({"x": ("x",)}), StaticRouter({"default": "local"}), DefaultPromptBuilder(), PolicyBroker(["x"], {"brain": ["x"]}), events, Dispatcher()); return RuntimeCoordinator(pipeline, DeliveryPipeline(QAGate()), events, fault_injector=fault), events
    def test_full_flow_delivers_and_reconstructs(self):
        runtime, events = self.make_runtime(); run = runtime.run("x", "session")
        self.assertTrue(run.delivered[0].success); self.assertEqual(run.state.status, "COMPLETED"); self.assertTrue(any(event.type == "JobCompleted" for event in events.all()))
    def test_fault_is_recorded_and_recoverable_from_replay(self):
        def fault(stage):
            if stage == "before_delivery": raise FaultInjected("crash before delivery")
        runtime, events = self.make_runtime(fault)
        with self.assertRaises(FaultInjected): runtime.run("x", "session")
        run_id = events.all()[0].run_id; self.assertEqual(runtime.recover(run_id).status, "FAILED")
    def test_cancel_during_delivery_is_persisted(self):
        runtime, events = self.make_runtime(); cancel = threading.Event(); cancel.set(); run = runtime.run("x", "session", cancel_event=cancel)
        self.assertEqual(run.state.status, "CANCELLED"); self.assertTrue(any(event.type == "JobCancelled" for event in events.all()))

    def test_api_discovery_admits_candidates_before_runtime_execution(self):
        runtime, events = self.make_runtime()
        catalog = DynamicApiCatalog()
        runtime = RuntimeCoordinator(runtime.pipeline, runtime.delivery, events, api_discovery=ApiDiscovery(catalog), api_candidates=(ApiCandidate("new", "model", "https://provider.example", ("x",), "default", "catalog", "MIT"),))
        self.assertEqual(len(runtime.discovered_providers), 1)
        self.assertEqual(catalog.entries()[0].provider, "new")
        self.assertTrue(any(event.type == "ProvidersDiscovered" for event in events.all()))

    def test_project_scan_and_readiness_are_connected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); (root / "tests").mkdir(); (root / "tests/test_ok.py").write_text("def test_ok(): pass", encoding="utf-8")
            events = EventStore(); pipeline = BrainPipeline(KeywordSecretary({"x": ("x",)}), StaticRouter({"default": "local"}), DefaultPromptBuilder(), PolicyBroker(["x"], {"brain": ["x"]}), events, Dispatcher())
            runtime = RuntimeCoordinator(pipeline, DeliveryPipeline(QAGate()), events, project_scanner=ProjectScanner(root), readiness_gate=ReadinessGate())
            run = runtime.run("x", "session")
            self.assertIsNotNone(run.readiness); self.assertEqual(run.readiness.status, "ready")
            self.assertTrue(any(event.type == "ProjectScanned" for event in events.all()))
            self.assertTrue(any(event.type == "ReadinessEvaluated" for event in events.all()))

if __name__ == "__main__": unittest.main()
