package com.brain.behavior

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BehaviorContractsTest {
    @Test fun `gate bloqueado exige issue bloqueante`() {
        runCatching { GateResult<String>(GateStatus.BLOCKED, issues = listOf(GateIssue("warn", "não bloqueante", false))) }
            .onSuccess { error("deveria rejeitar gate bloqueado sem issue bloqueante") }
    }

    @Test fun `verification só passa quando todos checks passam`() {
        val result = VerificationResult(
            VerificationStatus.PASSED,
            listOf(VerificationCheck("build", true, "build passou"), VerificationCheck("tests", false, "teste falhou"))
        )
        assertFalse(result.passed)
    }

    @Test fun `readiness bloqueado preserva blockers`() {
        val report = ReadinessReport(
            ReadinessStatus.BLOCKED,
            listOf(ReadinessStage("tests", false, detail = "não executado")),
            blockers = listOf("tests")
        )
        assertFalse(report.status == ReadinessStatus.READY)
        assertTrue(report.blockers.contains("tests"))
    }

    @Test fun `revision decision limita tentativas`() {
        runCatching { RevisionDecision(RevisionAction.REVISE, "corrigir", maxAttempts = 4) }
            .onSuccess { error("deveria rejeitar mais de três tentativas") }
    }
}
