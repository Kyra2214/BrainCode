import hashlib
import tempfile
import unittest
from pathlib import Path
from brain_runtime.credentials import CredentialRef, CredentialVault
from brain_runtime.events import EventStore
from brain_runtime.models import EventType
from brain_runtime.skill_adapters import ExternalSkill, GitSkillsAdapter
from brain_runtime.skills import SkillRegistry, StaticSkillTrustAuthority
from brain_runtime.workflows import WorkflowEngine, WorkflowManifest, WorkflowNode

class FinalHardeningTests(unittest.TestCase):
    def test_event_stream_sequence_rotation_and_compaction(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "events.jsonl"; store = EventStore(path, max_bytes=10_000)
            for i in range(3): store.append("r", "s", "t", EventType.TASK_CREATED, {"i": i})
            self.assertEqual(store.stream_sequence("r", "t"), 3); removed = store.compact(2); self.assertEqual(removed, 1); self.assertTrue(store.verify_integrity())

    def test_workflow_lease_renew_and_cancel(self):
        engine = WorkflowEngine(); manifest = WorkflowManifest("wf", "1", (WorkflowNode("a", "a"),))
        engine.run(manifest, "r", "key", lambda _: True, owner="one")
        # Completed workflows cannot be cancelled or renewed.
        with self.assertRaises(RuntimeError): engine.renew_lease("key", "one")

    def test_credential_is_injected_only_at_dispatch(self):
        vault = CredentialVault({"API_TOKEN": "secret-value"}); request = {"credential_ref": "API_TOKEN"}; captured = []
        vault.dispatch(lambda req, credential=None: captured.append(credential), request, CredentialRef("API_TOKEN"))
        self.assertEqual(captured, ["secret-value"]); self.assertEqual(request["credential_ref"], "API_TOKEN"); self.assertNotIn("secret-value", str(vault.sanitize(request)))

    def test_signed_external_skill_is_registered_and_revocable(self):
        with tempfile.TemporaryDirectory() as directory:
            registry = SkillRegistry(quarantine_path=Path(directory) / "quarantine.jsonl"); body = "safe skill"; commit = "abc123"; signature = hashlib.sha256((commit + body).encode()).hexdigest()
            manifest = GitSkillsAdapter().import_skill(ExternalSkill("git", "safe", "1", "safe", body, "MIT", verified=True, source_url="https://example", commit=commit, signature=signature), registry, directory)
            self.assertEqual(manifest.source_commit, commit); registry.revoke("safe", "test"); self.assertTrue(registry.is_revoked("safe"))

    def test_skill_requires_external_trust_authority_for_manifest_and_body(self):
        with tempfile.TemporaryDirectory() as directory:
            body = "authority-approved body"; digest = hashlib.sha256(body.encode()).hexdigest()
            authority = StaticSkillTrustAuthority({("safe", "1"): {"content_hash": digest, "source_commit": "abc123", "source_url": "https://example", "provenance": ("git", "https://example", "abc123")}})
            registry = SkillRegistry(trust_authority=authority, require_authority=True)
            manifest = GitSkillsAdapter().import_skill(ExternalSkill("git", "safe", "1", "safe", body, "MIT", verified=True, source_url="https://example", commit="abc123", signature=hashlib.sha256(("abc123" + body).encode()).hexdigest()), registry, directory)
            self.assertTrue(registry.validate_body("safe", directory))
            Path(directory, manifest.body_path).write_text("tampered", encoding="utf-8")
            self.assertFalse(registry.validate_body("safe", directory))

    def test_skill_authority_rejects_self_declared_hash_only(self):
        with tempfile.TemporaryDirectory() as directory:
            authority = StaticSkillTrustAuthority({("safe", "1"): {"content_hash": hashlib.sha256(b"different").hexdigest()}})
            registry = SkillRegistry(trust_authority=authority, require_authority=True)
            with self.assertRaises(PermissionError):
                GitSkillsAdapter().import_skill(ExternalSkill("git", "safe", "1", "safe", "unapproved body", "MIT", verified=True), registry, directory)

if __name__ == "__main__": unittest.main()
