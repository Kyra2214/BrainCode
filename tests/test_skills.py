import unittest
from brain_runtime.skills import SkillManifest, SkillRegistry, SpecialistRouter


class SkillTests(unittest.TestCase):
    def test_untrusted_skill_is_not_authority(self):
        registry = SkillRegistry()
        with self.assertRaises(PermissionError):
            registry.register(SkillManifest("bad", "1", "x", ("seo",), body_path="x", trust_level="community"))

    def test_router_selects_builtin_and_honors_exclusions(self):
        registry = SkillRegistry()
        registry.register(SkillManifest("geo", "1", "GEO", ("seo",), ("black hat",), body_path="geo.md", trust_level="builtin"))
        self.assertEqual(SpecialistRouter(registry).route("faça seo técnico").id, "geo")
        with self.assertRaises(LookupError):
            SpecialistRouter(registry).route("black hat seo")


if __name__ == "__main__":
    unittest.main()
