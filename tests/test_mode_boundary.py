import unittest
from brain_runtime.configuration import validate_configuration
from brain_runtime.contracts import ContractError
from brain_runtime.delivery import DeliveryPipeline, QAGate
from brain_runtime.events import EventStore
from brain_runtime.modes import RuntimeMode, validate_runtime_components
from brain_runtime.pipeline import BrainPipeline, DefaultPromptBuilder, KeywordSecretary, StaticRouter
from brain_runtime.policy import PolicyBroker
from brain_runtime.readiness import ReadinessGate
from brain_runtime.runtime import RuntimeCoordinator


class ModeBoundaryTests(unittest.TestCase):
    def test_protected_modes_cannot_disable_sandbox_or_readiness(self):
        for mode in (RuntimeMode.OFFLINE, RuntimeMode.SANDBOXED, RuntimeMode.STRICT, RuntimeMode.PRODUCTION):
            with self.assertRaises(RuntimeError):
                validate_runtime_components(mode, has_qa=True, has_observability=True, has_dispatcher=True, has_policy=True, sandbox_enabled=False)
            with self.assertRaises(RuntimeError):
                validate_runtime_components(mode, has_qa=True, has_observability=True, has_dispatcher=True, has_policy=True, readiness_enabled=False)
        validate_runtime_components(RuntimeMode.DEVELOPMENT, has_qa=False, has_observability=False, has_dispatcher=False, has_policy=True, sandbox_enabled=False, readiness_enabled=False)

    def test_common_configuration_cannot_turn_off_protected_controls(self):
        base = {"policy": {"deny_by_default": True}, "providers": {}, "routing": {}}
        with self.assertRaises(ContractError):
            validate_configuration({**base, "execution": {"mode": "production", "sandbox_enabled": False}})
        with self.assertRaises(ContractError):
            validate_configuration({**base, "execution": {"mode": "strict", "readiness_enabled": False}})

    def test_production_runtime_requires_readiness_and_cannot_bypass_coordinator_configuration(self):
        events = EventStore()
        pipeline = BrainPipeline(KeywordSecretary({"x": ("x",)}), StaticRouter({"default": "local"}), DefaultPromptBuilder(), PolicyBroker(["x"], {"brain": ["x"]}), events, object())
        with self.assertRaises((RuntimeError, ValueError)):
            RuntimeCoordinator(pipeline, DeliveryPipeline(QAGate()), events, mode="production")
        with self.assertRaises((RuntimeError, ValueError)):
            RuntimeCoordinator(pipeline, DeliveryPipeline(QAGate()), events, mode="production", readiness_gate=ReadinessGate(), enforce_readiness=False)


if __name__ == "__main__":
    unittest.main()
