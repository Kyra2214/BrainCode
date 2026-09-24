import unittest
from brain_runtime.apis import ApiCatalogEntry, DynamicApiCatalog


class ApiTests(unittest.TestCase):
    def test_waterfall_uses_second_provider_after_failure(self):
        catalog = DynamicApiCatalog([
            ApiCatalogEntry("a", "m1", ("research",), "g", reliability=.9),
            ApiCatalogEntry("b", "m2", ("research",), "g", reliability=.8),
        ])
        calls = []
        def call(entry):
            calls.append(entry.provider)
            if entry.provider == "a":
                raise RuntimeError("quota")
            return "ok"
        selected, result = catalog.waterfall("research", call)
        self.assertEqual(selected.provider, "b")
        self.assertEqual(result, "ok")
        self.assertEqual(calls, ["a", "b"])

    def test_exhausted_quota_is_not_candidate(self):
        catalog = DynamicApiCatalog([ApiCatalogEntry("a", "m", ("x",), "g", quota_remaining=0)])
        with self.assertRaises(LookupError):
            catalog.waterfall("x", lambda _: "never")


if __name__ == "__main__":
    unittest.main()
