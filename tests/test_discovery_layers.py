import tempfile
import unittest
from pathlib import Path
from brain_runtime.api_discovery import ApiCandidate, ApiDiscovery
from brain_runtime.apis import DynamicApiCatalog
from brain_runtime.research import ResearchLayer, ResearchSource
from brain_runtime.skill_adapters import ExternalSkill, GitSkillsAdapter
from brain_runtime.skills import SkillRegistry

class DiscoveryLayerTests(unittest.TestCase):
    def test_research_keeps_evidence_hash_and_rejects_http(self):
        layer = ResearchLayer(lambda source: "claim from source")
        result = layer.collect("claim", (ResearchSource("a", "https://example.com/a", "A", trust_score=.9), ResearchSource("b", "http://bad", "B")))
        self.assertEqual(len(result.evidence), 1); self.assertTrue(ResearchLayer.validate(result)[0]); self.assertIn("source rejected: b", result.limitations)

    def test_unverified_skill_is_quarantined(self):
        with tempfile.TemporaryDirectory() as directory:
            registry = SkillRegistry()
            with self.assertRaises(PermissionError): GitSkillsAdapter().import_skill(ExternalSkill("git", "x", "1", "x", "body", "MIT"), registry, directory)
            self.assertEqual(registry.all(), ())

    def test_verified_skill_is_hashed_and_registered(self):
        with tempfile.TemporaryDirectory() as directory:
            registry = SkillRegistry()
            manifest = GitSkillsAdapter().import_skill(ExternalSkill("git", "x", "1", "x", "body", "MIT", verified=True), registry, directory)
            self.assertTrue(registry.validate_body("x", directory)); self.assertEqual(manifest.license, "MIT")

    def test_api_discovery_requires_license_and_records_provenance(self):
        catalog = DynamicApiCatalog(); discovery = ApiDiscovery(catalog)
        entries = discovery.ingest((ApiCandidate("p", "m", "https://p", ("x",), "g", "catalog", "MIT", .1), ApiCandidate("bad", "m", "x", ("x",), "g", "catalog")))
        self.assertEqual(len(entries), 1); self.assertEqual(entries[0].provenance[0], "catalog")

if __name__ == "__main__": unittest.main()
