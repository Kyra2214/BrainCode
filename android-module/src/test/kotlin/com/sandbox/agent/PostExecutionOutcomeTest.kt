package com.sandbox.agent

import com.brain.behavior.CritiqueResult
import com.brain.behavior.CritiqueStatus
import com.brain.behavior.ReadinessReport
import com.brain.behavior.ReadinessStage
import com.brain.behavior.ReadinessStatus
import com.brain.behavior.RevisionAction
import com.brain.behavior.RevisionDecision
import com.brain.behavior.VerificationCheck
import com.brain.behavior.VerificationResult
import com.brain.behavior.VerificationStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PostExecutionOutcomeTest {
    @Test fun `somente todos os gates aprovados produzem aprovado`() {
        assertTrue(outcome().aprovado)
    }

    @Test fun `critic needs revision nunca retorna ready`() {
        assertFalse(outcome(critique = CritiqueStatus.NEEDS_REVISION).aprovado)
    }

    @Test fun `readiness blocked nunca retorna ready mesmo com verification passed`() {
        assertFalse(outcome(readiness = ReadinessStatus.BLOCKED).aprovado)
    }

    @Test fun `revision revise nunca retorna ready`() {
        assertFalse(outcome(revision = RevisionAction.REVISE).aprovado)
    }

    @Test fun `verification failed nunca retorna ready`() {
        assertFalse(outcome(verification = VerificationStatus.FAILED).aprovado)
    }

    @Test fun `resultado do ciclo acompanha o bloqueio pos execucao`() {
        val cycle = ResultadoCiclo(
            objetivo = "e2e",
            runId = "run-e2e",
            passos = listOf(ResultadoPasso("step", StatusPasso.APROVADO, resultado = "resposta")),
            posExecucao = outcome(critique = CritiqueStatus.NEEDS_REVISION)
        )
        assertFalse(cycle.aprovado)
    }

    private fun outcome(
        verification: VerificationStatus = VerificationStatus.PASSED,
        critique: CritiqueStatus = CritiqueStatus.PASS,
        revision: RevisionAction = RevisionAction.ACCEPT,
        readiness: ReadinessStatus = ReadinessStatus.READY
    ) = ResultadoPosExecucao(
        verification = VerificationResult(verification, listOf(VerificationCheck("criterion", verification == VerificationStatus.PASSED, "evidence"))),
        critique = CritiqueResult(critique, if (critique == CritiqueStatus.PASS) emptyList() else listOf(com.brain.behavior.CritiqueFinding("finding", "requires review"))),
        revision = RevisionDecision(revision, "e2e decision"),
        readiness = ReadinessReport(readiness, listOf(ReadinessStage("tests", readiness == ReadinessStatus.READY)), blockers = if (readiness == ReadinessStatus.BLOCKED) listOf("blocked by e2e") else emptyList()),
        learningRecorded = false
    )
}
