package com.brain.behavior

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FixVerifyLearnTest {
    @Test fun `ciclo executa todas as fases em ordem e aprende após verificação`() {
        val calls = mutableListOf<String>()
        val result = FixVerifyLearn(
            object : ScanStage<String> { override fun scan() = "bug".also { calls += "scan" } },
            object : FixPlanStage<String, String> { override fun plan(scan: String) = "plan:$scan".also { calls += "plan" } },
            object : FixStage<String, String> { override fun fix(plan: String) = "fix:$plan".also { calls += "fix" } },
            object : VerifyStage<String> { override fun verify(fix: String) = VerificationResult(VerificationStatus.PASSED, listOf(VerificationCheck("fix", true, fix))).also { calls += "verify" } },
            object : LearnStage<String, String> { override fun learn(fix: String, verification: VerificationResult) = "learn:$fix".also { calls += "learn" } }
        ).run()
        assertEquals(FixVerifyLearnStatus.COMPLETED, result.status)
        assertEquals(listOf("scan", "plan", "fix", "verify", "learn"), calls)
        assertEquals("learn:fix:plan:bug", result.learned)
    }

    @Test fun `ciclo bloqueia sem aprender quando verificacao falha`() {
        var learned = false
        val result = FixVerifyLearn(
            object : ScanStage<String> { override fun scan() = "bug" },
            object : FixPlanStage<String, String> { override fun plan(scan: String) = "plan" },
            object : FixStage<String, String> { override fun fix(plan: String) = "fix" },
            object : VerifyStage<String> { override fun verify(fix: String) = VerificationResult(VerificationStatus.FAILED, listOf(VerificationCheck("test", false, "failed"))) },
            object : LearnStage<String, Unit> { override fun learn(fix: String, verification: VerificationResult) { learned = true } }
        ).run()
        assertEquals(FixVerifyLearnStatus.BLOCKED, result.status)
        assertTrue(!learned)
    }
}
