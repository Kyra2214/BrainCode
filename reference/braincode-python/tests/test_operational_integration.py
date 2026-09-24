import tempfile
import unittest
from pathlib import Path
from brain_runtime.approval import ApprovalStore
from brain_runtime.configuration import validate_cross_component
from brain_runtime.dispatch import SandboxDispatcher
from brain_runtime.instrumentation import InstrumentedApprovalStore, InstrumentedPlanner, InstrumentedPolicy, InstrumentedRouter
from brain_runtime.models import ApprovalRequired, ExecutionRequest, PolicyContext, Decision
from brain_runtime.observability import Observability
from brain_runtime.planner import Planner
from brain_runtime.policy import PolicyBroker
from brain_runtime.sandbox import SandboxExecutor, SandboxJob
from brain_runtime.pipeline import StaticRouter

class OperationalIntegrationTests(unittest.TestCase):
    def test_instrumented_components_emit_metrics(self):
        telemetry = Observability(); policy = InstrumentedPolicy(PolicyBroker(["x"], {"a": ["x"]}), telemetry); decision = policy.authorize("a", "x", "local", PolicyContext("r", "t", "a")); self.assertEqual(decision.decision, Decision.ALLOW)
        InstrumentedPlanner(Planner(), telemetry).create(type("Task", (), {"objective": "análise", "capabilities": ("analysis",), "task_id": "t", "success_criteria": (), "session_id": "s"})())
        InstrumentedRouter(StaticRouter({"default": "local"}), telemetry).select("x")
        self.assertGreaterEqual(telemetry.counters()["policy.decision:ALLOW"], 1); self.assertEqual(telemetry.counters()["planner.created"], 1)

    def test_approval_telemetry(self):
        telemetry = Observability(); inner = ApprovalStore(); wrapped = InstrumentedApprovalStore(inner, telemetry)
        decision = PolicyBroker(["x"], {"a": ["x"]}).authorize("a", "x", "local", PolicyContext("r", "t", "a", approval=ApprovalRequired.USER))
        # ApprovalRequired is an enum in normal callers; this assertion only verifies wrapper shape.
        if decision.decision.value == "ASK": wrapped.request(decision)
        self.assertEqual(telemetry.counters()["approval.requested"], 1)

    def test_dispatcher_rejects_tampered_binding_and_runs_valid_job(self):
        with tempfile.TemporaryDirectory() as directory:
            def factory(request): return SandboxJob("job", request.run_id, "session", ("python3", "-c", "print('ok')"), directory)
            dispatcher = SandboxDispatcher(SandboxExecutor(), factory)
            bad = ExecutionRequest("req", "r", "t", "s", "x", "objective", {"provider": "p", "binding_digest": "tampered"}, "decision")
            self.assertEqual(dispatcher.dispatch(bad).status, "REJECTED")
            from brain_runtime.binding import bind_execution
            binding = bind_execution(run_id="r", task_id="t", step_id="s", capability="x", provider="p", policy_decision_id="decision", resource="p")
            good = ExecutionRequest("req2", "r", "t", "s", "x", "objective", {"provider": "p", "binding_digest": binding.digest()}, "decision")
            self.assertTrue(dispatcher.dispatch(good).success)

    def test_cross_component_config_rejects_conflict(self):
        config = {"policy": {"deny_by_default": True, "allow_network": False, "filesystem_roots": ["/safe"], "allowed_capabilities": ["read"]}, "execution": {"network_required": True, "filesystem_roots": ["/safe"]}, "providers": {}, "routing": {"capabilities": ["read"]}}
        with self.assertRaises(Exception): validate_cross_component(config)

if __name__ == "__main__": unittest.main()
