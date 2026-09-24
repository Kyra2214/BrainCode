import subprocess
import tempfile
import unittest
from pathlib import Path

from brain_runtime.context_pack import ContextBuilder
from brain_runtime.evidence import Evidence, EvidenceEngine
from brain_runtime.fix_verify_learn import FixVerifyLearn
from brain_runtime.project_intelligence import ProjectScanner
from brain_runtime.readiness import ReadinessGate
from brain_runtime.release_intelligence import ReleaseIntelligence


class ProjectIntelligencePlanTests(unittest.TestCase):
    def test_project_scanner_writes_redacted_context_files(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "brain_runtime").mkdir(); (root / "tests").mkdir(); (root / "config/providers").mkdir(parents=True)
            (root / "brain_runtime/main.py").write_text("provider_local = True", encoding="utf-8")
            (root / "tests/test_main.py").write_text("def test_ok(): pass", encoding="utf-8")
            (root / "config/providers/local.json").write_text("{}", encoding="utf-8")
            snapshot = ProjectScanner(root).scan(); output = ProjectScanner(root).write_context(snapshot)
            self.assertIn("tests/test_main.py", snapshot.tests)
            self.assertIn("local", snapshot.providers)
            self.assertTrue((output / "context.json").exists())

    def test_readiness_gate_blocks_and_exposes_exit_code(self):
        report = ReadinessGate().evaluate({"implementation": True, "tests": False}, blockers=("credential unresolved",), warnings=("no release tag",))
        self.assertEqual(report.status, "blocked"); self.assertEqual(report.exit_code, 1); self.assertGreaterEqual(report.critical, 2)
        ready = ReadinessGate().evaluate({name: True for name in ReadinessGate.ORDER})
        self.assertEqual(ready.status, "ready"); self.assertEqual(ready.exit_code, 0)

    def test_release_intelligence_compares_commits_and_flags_untested_runtime_change(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); subprocess.run(["git", "init", "-q", str(root)], check=True)
            (root / "brain_runtime").mkdir(); (root / "brain_runtime/provider.py").write_text("old", encoding="utf-8"); subprocess.run(["git", "-C", str(root), "add", "."], check=True); subprocess.run(["git", "-C", str(root), "-c", "user.email=x@y", "-c", "user.name=x", "commit", "-qm", "base"], check=True)
            base = subprocess.check_output(["git", "-C", str(root), "rev-parse", "HEAD"], text=True).strip(); (root / "brain_runtime/provider.py").write_text("new", encoding="utf-8"); subprocess.run(["git", "-C", str(root), "add", "."], check=True); subprocess.run(["git", "-C", str(root), "-c", "user.email=x@y", "-c", "user.name=x", "commit", "-qm", "change"], check=True); result = ReleaseIntelligence(root).compare(base)
            self.assertEqual(result.changes[0].area, "runtime"); self.assertEqual(result.confidence, .87)

    def test_evidence_engine_requires_verified_evidence_and_external_state(self):
        engine = EvidenceEngine(); report = engine.assess("Stripe configured", (Evidence("dependency", "stripe dependency", verified=True),), required_kinds=("dependency", "integration"))
        self.assertEqual(report.decision, "blocked"); self.assertIn("integration", report.missing)
        with self.assertRaises(ValueError): Evidence("x", "api_key=secret")

    def test_context_builder_and_fix_verify_learn(self):
        pack = ContextBuilder().build({"objective": "test"}, risks=("risk",), providers=("local",))
        self.assertIn("CONTEXT PACK", pack.to_prompt())
        scanner = type("Scanner", (), {"scan": lambda self: {"files": 1}})()
        cycle = FixVerifyLearn(scanner, lambda scan: {"task": scan}, lambda task: task, FixVerifyLearn.default_verifier, lambda fix: "learned")
        result = cycle.run(); self.assertEqual(result.verification.status, "ready"); self.assertEqual(result.learned, "learned")


if __name__ == "__main__": unittest.main()
