package com.brain.behavior

import com.brain.planner.PassoPlano
import com.brain.planner.PlanoExecucao
import com.brain.reasoning.ReasoningEngine
import com.brain.reasoning.ReasoningIntent
import com.brain.reasoning.ReasoningState
import com.brain.prompt.PromptDomain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BehaviorGatesTest {
    @Test fun `requirement gate pede esclarecimento quando há lacuna`() {
        val state = ReasoningState("objetivo", ReasoningIntent.GENERAL_TEXT, PromptDomain.TEXTO, emptyList(), emptyList(), listOf("sujeito"))
        val result = RequirementGate().evaluate(state)
        assertEquals(GateStatus.NEEDS_CLARIFICATION, result.status)
    }

    @Test fun `planning gate aceita plano com criterios`() {
        val plan = PlanoExecucao("objetivo", listOf(PassoPlano("x", "capability", "sucesso")))
        assertEquals(GateStatus.PASSED, PlanningGate().evaluate(plan).status)
    }

    @Test fun `planning gate bloqueia metodo de verificacao sem alvo`() {
        val criteria = listOf(AcceptanceCriteria("x", "sucesso", verification = "exists"))
        val plan = PlanoExecucao("objetivo", listOf(PassoPlano("x", "capability", "sucesso", acceptanceCriteria = criteria)))
        val result = PlanningGate().evaluate(plan)
        assertEquals(GateStatus.BLOCKED, result.status)
        assertTrue(result.issues.any { it.code == "invalid.verification" })
    }

    @Test fun `verification gate bloqueia check falho`() {
        val verification = VerificationResult(VerificationStatus.FAILED, listOf(VerificationCheck("x", false, "falhou")))
        assertEquals(GateStatus.FAILED, VerificationGate().evaluate(verification).status)
    }

    @Test fun `readiness gate exige todos os estágios`() {
        val report = ReadinessReport(ReadinessStatus.BLOCKED, listOf(ReadinessStage("tests", false)), listOf("tests"))
        assertEquals(GateStatus.BLOCKED, ReadinessGate().evaluate(report).status)
        assertTrue(ReadinessGate().evaluate(report).issues.isNotEmpty())
    }
}
