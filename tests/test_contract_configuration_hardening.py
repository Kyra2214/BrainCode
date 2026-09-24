import json
import tempfile
import unittest
from pathlib import Path
from brain_runtime.configuration import ConfigurationManager, validate_configuration
from brain_runtime.contract_registry import ContractRegistry
from brain_runtime.contracts import ContractError
from brain_runtime.events import EventStore
from brain_runtime.models import EventType

class ContractConfigurationTests(unittest.TestCase):
    def test_standard_registry_validates_core_contracts(self):
        registry = ContractRegistry.standard()
        payload = registry.validate("ExecutionRequest", {"schemaVersion": 1, "request_id": "r", "run_id": "run", "task_id": "t", "step_id": "s", "capability": "x", "objective": "o", "policy_decision_id": "d"})
        self.assertEqual(payload["request_id"], "r")
        with self.assertRaises(ContractError): registry.validate("ExecutionResult", {"schemaVersion": 1, "request_id": "r", "success": "yes", "status": "SUCCEEDED"})

    def test_configuration_manager_loads_and_reloads_atomically(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for section, value in {"policy": {"deny_by_default": True}, "execution": {"timeout_seconds": 10}, "providers": {}, "routing": {}}.items():
                (root / f"{section}.json").write_text(json.dumps(value), encoding="utf-8")
            manager = ConfigurationManager(root); self.assertEqual(manager.load()["policy"]["deny_by_default"], True)
            (root / "execution.json").write_text(json.dumps({"timeout_seconds": 20}), encoding="utf-8")
            self.assertEqual(manager.reload()["execution"]["timeout_seconds"], 20)
            with self.assertRaises(ContractError): validate_configuration({"policy": {"deny_by_default": False}, "execution": {}, "providers": {}, "routing": {}})

    def test_protected_modes_cannot_disable_readiness_in_configuration(self):
        base = {"policy": {"deny_by_default": True}, "execution": {}, "providers": {}, "routing": {}}
        self.assertEqual(validate_configuration({**base, "execution": {"mode": "development", "enforce_readiness": False}})["execution"]["mode"], "development")
        with self.assertRaises(ContractError): validate_configuration({**base, "execution": {"mode": "production", "enforce_readiness": False}})
        with self.assertRaises(ContractError): validate_configuration({**base, "execution": {"mode": "strict", "enforce_readiness": False}})

    def test_event_correlation_id_is_persisted(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "events.jsonl"; store = EventStore(path)
            event = store.append("run", "session", "task", EventType.TASK_CREATED, {}, correlation_id="trace-1")
            restored = EventStore(path)
            self.assertEqual(event.correlation_id, "trace-1"); self.assertEqual(restored.all()[0].correlation_id, "trace-1")

if __name__ == "__main__": unittest.main()
