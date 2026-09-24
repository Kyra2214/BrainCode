import tempfile
import unittest
from pathlib import Path
from brain_runtime.contracts import ContractError, validate_payload
from brain_runtime.events import EventStore
from brain_runtime.memory import Experience, SQLiteExperienceMemory, SemanticMemory
from brain_runtime.models import EventType, PolicyContext
from brain_runtime.planner import Planner, ValidationLoop
from brain_runtime.policy import PolicyBroker
from brain_runtime.skills import SkillManifest, SkillRegistry

class RoadmapFeatureTests(unittest.TestCase):
    def test_event_idempotency_and_integrity(self):
        with tempfile.TemporaryDirectory() as directory:
            store = EventStore(Path(directory) / "events.jsonl")
            first = store.append("r", "s", "t", EventType.TASK_CREATED, {"token": "secret"}, "same")
            second = store.append("r", "s", "t", EventType.TASK_CREATED, {"other": True}, "same")
            self.assertEqual(first.event_id, second.event_id)
            self.assertTrue(store.verify_integrity())

    def test_planner_and_validation_loop(self):
        from brain_runtime.models import TaskSpec
        plan = Planner().create(TaskSpec("t", "s", "pesquisar e analisar"))
        self.assertGreaterEqual(len(plan.steps), 2)
        calls = []
        result, diagnostics, attempt = ValidationLoop(lambda result: (result.success, ())).run(lambda n, d: calls.append(n) or __import__('brain_runtime.models', fromlist=['ExecutionResult']).ExecutionResult("r", n > 0), 1)
        self.assertTrue(result.success); self.assertEqual(attempt, 1)

    def test_memory_requires_provenance_and_rejects_secrets(self):
        memory = SemanticMemory(SQLiteExperienceMemory())
        base = Experience("e", "r", "t", "problem", "source", "strategy", "result", .5)
        with self.assertRaises(ValueError): memory.remember(base)
        with self.assertRaises(ValueError): memory.remember(Experience("e2", "r", "t", "problem", "source", "strategy", "token=bad", .5, provenance=("event",)))

    def test_contract_schema_version(self):
        self.assertEqual(validate_payload({"id": "x", "schemaVersion": 1}, ("id",))["id"], "x")
        with self.assertRaises(ContractError): validate_payload({"schemaVersion": 2}, ("id",))

    def test_skill_permission_and_body_isolation(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); (root / "body.md").write_text("safe")
            registry = SkillRegistry(); registry.register(SkillManifest("x", "1", "x", ("x",), body_path="body.md", required_permissions=("read",), trust_level="builtin"))
            self.assertTrue(registry.validate_body("x", root)); self.assertTrue(registry.authorize("x", ("read",)))
            self.assertFalse(registry.authorize("x", ()))

if __name__ == "__main__": unittest.main()
