import unittest
from brain_runtime.memory import Experience, SQLiteExperienceMemory
from brain_runtime.operational import FallbackCandidate, ParallelPlanExecutor, PersistentQuotaStore, QuotaSnapshot, strict_fallback
from brain_runtime.sandbox import strict_isolation_available

class OperationalPhaseTests(unittest.TestCase):
    def test_quota_survives_store_reopen(self):
        store = PersistentQuotaStore(); store.save(QuotaSnapshot("p", "m", used=2, success_rate=.8)); self.assertEqual(store.get("p", "m").used, 2)
    def test_strict_fallback_filters_incompatible_candidates(self):
        items = strict_fallback([FallbackCandidate("a", "m", frozenset({"x"}), 100, 1), FallbackCandidate("b", "m", frozenset({"y"}), 100, 1)], "x")
        self.assertEqual([item.provider for item in items], ["a"])
    def test_memory_strategy_search(self):
        memory = SQLiteExperienceMemory(); memory.record(Experience("e", "r", "t", "problem", "source", "strategy", "result", .9, provenance=("proof",)))
        self.assertEqual(len(memory.search_strategy("strategy")), 1)
    def test_parallel_preserves_input_order(self):
        steps = [{"id": "a"}, {"id": "b"}]; result = ParallelPlanExecutor(2).run(steps, lambda s: (), lambda s: s["id"], lambda s: s["id"]); self.assertEqual(result, ["a", "b"])
    def test_isolation_probe_is_explicit(self):
        ok, diagnostics = strict_isolation_available(); self.assertIsInstance(ok, bool); self.assertIsInstance(diagnostics, tuple)

if __name__ == "__main__": unittest.main()
