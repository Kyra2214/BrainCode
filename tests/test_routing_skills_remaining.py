import hashlib
import tempfile
import time
import unittest
from datetime import datetime, timedelta, timezone
from dataclasses import replace
from pathlib import Path

from brain_runtime.api_discovery import ApiCandidate, ApiDiscovery
from brain_runtime.apis import ApiCatalogEntry, DynamicApiCatalog
from brain_runtime.credentials import CredentialRef, CredentialVault
from brain_runtime.skill_adapters import ExternalSkill, GitSkillsAdapter
from brain_runtime.skills import SkillManifest, SkillRegistry


class RoutingSkillsRemainingTests(unittest.TestCase):
    def test_reservation_and_reconciliation_survive_catalog_restart(self):
        with tempfile.TemporaryDirectory() as directory:
            state = Path(directory) / "catalog.json"
            entry = ApiCatalogEntry("provider", "model", ("research",), "research", quota_remaining=3)
            first = DynamicApiCatalog([entry], persistence_path=state)
            first.reserve("provider", "model")

            restarted = DynamicApiCatalog(state_path=state)
            loaded = restarted.entries()[0]
            self.assertEqual(loaded.reserved_quota, 1)
            restarted.reconcile("provider", "model", quota_remaining=2)

            reconciled = DynamicApiCatalog(state_path=state).entries()[0]
            self.assertEqual(reconciled.reserved_quota, 0)
            self.assertEqual(reconciled.quota_remaining, 2)

    def test_quota_window_resets_and_cooldown_recovers(self):
        entry = ApiCatalogEntry(
            "provider", "model", ("x",), "group", quota_limit=2,
            quota_window_seconds=0.01,
            quota_window_started_at=(datetime.now(timezone.utc) - timedelta(seconds=1)).isoformat(),
            quota_remaining=0,
        )
        catalog = DynamicApiCatalog([entry])
        catalog.reserve("provider", "model", 2)
        with self.assertRaises(LookupError):
            catalog.reserve("provider", "model")

        time.sleep(0.02)
        entry.quota_window_started_at = (datetime.now(timezone.utc) - timedelta(seconds=1)).isoformat()
        entry.quota_remaining = 0
        entry.healthy = False
        entry.cooldown_until = (datetime.now(timezone.utc) - timedelta(seconds=1)).isoformat()
        self.assertEqual(catalog.select("x").provider, "provider")
        self.assertEqual(entry.reserved_quota, 0)
        self.assertEqual(entry.quota_remaining, 2)
        self.assertTrue(entry.healthy)

    def test_waterfall_filters_equivalent_capabilities_by_fallback_group(self):
        catalog = DynamicApiCatalog([
            ApiCatalogEntry("wrong", "m", ("x",), "other", quality=1.0),
            ApiCatalogEntry("fallback", "m", ("x",), "approved", quality=.8),
        ])
        selected, result = catalog.waterfall("x", lambda item: "ok", fallback_group="approved")
        self.assertEqual((selected.provider, result), ("fallback", "ok"))

    def test_api_discovery_rejects_bad_provenance_and_accepts_signed_metadata(self):
        catalog = DynamicApiCatalog()
        unsigned = ApiCandidate("bad", "m", "https://bad", ("x",), "g", "catalog", "MIT", provenance=("file:///secret",))
        discovery = ApiDiscovery(catalog, require_signature=True)
        self.assertEqual(discovery.ingest((unsigned,)), ())

        candidate = ApiCandidate("good", "m", "https://good", ("x",), "g", "catalog", "MIT", provenance=("https://catalog",))
        signed = replace(candidate, signature=hashlib.sha256(candidate.signing_payload()).hexdigest(), signature_key_id="catalog-digest")
        registered = ApiDiscovery(DynamicApiCatalog()).ingest((signed,))
        self.assertEqual(len(registered), 1)
        self.assertTrue(registered[0].signature_verified)
        self.assertIn("https://catalog", registered[0].provenance)

    def test_skill_license_signature_and_version_revocation_are_enforced(self):
        with tempfile.TemporaryDirectory() as directory:
            registry = SkillRegistry(quarantine_path=Path(directory) / "quarantine.jsonl")
            body = "safe skill"
            commit = "immutable-commit"
            signature = hashlib.sha256((commit + body).encode()).hexdigest()
            manifest = GitSkillsAdapter().import_skill(
                ExternalSkill("git", "skill", "1", "safe", body, "MIT", verified=True, commit=commit, signature=signature),
                registry,
                directory,
            )
            registry.revoke("skill", version="1")
            self.assertTrue(registry.is_revoked("skill", "1"))
            self.assertEqual(registry.all(), ())
            v2 = replace(manifest, version="2", body_path="skill-v2.md")
            Path(directory, v2.body_path).write_text(body, encoding="utf-8")
            registry.register(v2)
            self.assertEqual(registry.get("skill").version, "2")
            with self.assertRaises(PermissionError):
                GitSkillsAdapter().import_skill(ExternalSkill("git", "bad", "1", "bad", body, "Unlicensed", verified=True), registry, directory)

    def test_revoked_credentials_and_secret_redaction_never_leak_values(self):
        vault = CredentialVault({"API_TOKEN": "super-secret-value"})
        request = {"credential_ref": "API_TOKEN", "authorization": "Bearer super-secret-value", "nested": ["api_key=super-secret-value"]}
        sanitized = vault.sanitized(request)
        self.assertNotIn("super-secret-value", repr(sanitized))
        captured = []
        vault.dispatch(lambda req, credential=None: captured.append(credential), request, CredentialRef("API_TOKEN"))
        self.assertEqual(captured, ["super-secret-value"])
        vault.revoke("API_TOKEN")
        with self.assertRaises(PermissionError):
            vault.resolve_reference("API_TOKEN")
        self.assertEqual(request["credential_ref"], "API_TOKEN")


if __name__ == "__main__":
    unittest.main()
