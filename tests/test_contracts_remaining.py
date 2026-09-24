import unittest

from brain_runtime.contract_registry import ContractRegistry, ContractSchema
from brain_runtime.contracts import (
    ContractError,
    contract_from_payload,
    contract_payload,
    migrate_payload,
    validate_payload,
)
from brain_runtime.models import (
    Capability,
    Plan,
    PlanStep,
    PolicyDecision,
    TaskSpec,
)


class ContractRemainingTests(unittest.TestCase):
    def test_standard_registry_is_formal_and_validates_nested_plan_steps(self):
        registry = ContractRegistry.standard()
        payload = {
            "schemaVersion": 1,
            "plan_id": "plan-1",
            "task_id": "task-1",
            "steps": [
                {
                    "step_id": "step-1",
                    "description": "Research",
                    "capability": "research",
                    "dependencies": ["step-0"],
                }
            ],
        }
        validated = registry.validate("Plan", payload)
        self.assertEqual(validated["steps"][0]["capability"], "research")
        with self.assertRaisesRegex(ContractError, r"steps\[0\]\.capability"):
            registry.validate(
                "Plan",
                {**payload, "steps": [{**payload["steps"][0], "capability": 42}]},
            )

    def test_standard_registry_round_trips_nested_dataclasses(self):
        registry = ContractRegistry.standard()
        value = Plan(
            "plan-1",
            "task-1",
            (PlanStep("step-1", "Research", "research", dependencies=("step-0",)),),
            delivery_criteria=("evidence",),
        )
        payload = registry.to_payload("Plan", value)
        restored = registry.from_payload("Plan", payload)
        self.assertEqual(restored, value)
        self.assertIsInstance(restored.steps[0], PlanStep)
        self.assertIsInstance(payload["steps"], tuple)

    def test_contract_payload_round_trip_preserves_optional_and_enum_fields(self):
        decision = PolicyDecision(
            "decision-1",
            "run-1",
            "task-1",
            "brain",
            "research",
            "LOW",
            "ALLOW",
            "NONE",
            True,
            False,
            (),
            {},
            "2030-01-01T00:00:00+00:00",
            "approved",
        )
        payload = contract_payload(decision)
        restored = contract_from_payload(payload, PolicyDecision)
        self.assertEqual(restored.decision.value, "ALLOW")
        self.assertEqual(restored.approval_required.value, "NONE")
        self.assertEqual(restored, decision)

    def test_nested_type_validation_is_strict_for_bool_and_integer(self):
        with self.assertRaisesRegex(ContractError, "success"):
            validate_payload(
                {"schemaVersion": 1, "request_id": "r", "success": 1, "status": "SUCCEEDED"},
                ("request_id", "success", "status"),
                types={"request_id": str, "success": bool, "status": str},
            )
        with self.assertRaisesRegex(ContractError, r"items\[0\]"):
            validate_payload(
                {"schemaVersion": 1, "items": ["not-an-int"]},
                ("items",),
                types={"items": list[int]},
            )

    def test_registry_migrates_each_version_step_and_validates_latest(self):
        registry = ContractRegistry()
        registry.register(ContractSchema("Example", 1, ("id",), types={"id": str, "legacy": str}))
        registry.register(
            ContractSchema(
                "Example",
                2,
                ("id", "name"),
                types={"id": str, "name": str},
                migrations={1: lambda payload: {"id": payload["id"], "name": payload["legacy"]}},
            )
        )
        migrated = registry.validate(
            "Example", {"schemaVersion": 1, "id": "x", "legacy": "old"}
        )
        self.assertEqual(migrated, {"schemaVersion": 2, "id": "x", "name": "old"})
        self.assertEqual(
            registry.validate("Example", {"schemaVersion": 1, "id": "x", "legacy": "old"}, version=1)["legacy"],
            "old",
        )
        with self.assertRaises(ContractError):
            registry.validate("Example", {"schemaVersion": 3, "id": "x", "name": "new"})

    def test_migrate_payload_supports_v0_and_rejects_invalid_paths(self):
        self.assertEqual(
            migrate_payload({"id": "x"}, 0),
            {"id": "x", "schemaVersion": 1},
        )
        with self.assertRaises(ContractError):
            migrate_payload({"id": "x"}, 2, 1)
        with self.assertRaises(ContractError):
            migrate_payload({"id": "x"}, 1, 2)

    def test_standard_registry_covers_runtime_contract_models(self):
        registry = ContractRegistry.standard()
        for name in ("TaskSpec", "Plan", "PlanStep", "ExecutionRequest", "ExecutionResult", "Event"):
            self.assertIsNotNone(registry.schema(name).model)
        self.assertEqual(registry.schema("SandboxJob").version, 1)
        task = TaskSpec("task-1", "session-1", "objective")
        self.assertEqual(registry.from_payload("TaskSpec", registry.to_payload("TaskSpec", task)), task)
        capability = Capability("cap-1", "research")
        self.assertEqual(registry.from_payload("Capability", registry.to_payload("Capability", capability)), capability)


if __name__ == "__main__":
    unittest.main()
