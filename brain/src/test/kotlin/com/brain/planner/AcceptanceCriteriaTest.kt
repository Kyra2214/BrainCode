package com.brain.planner

import com.brain.behavior.AcceptanceCriteria
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AcceptanceCriteriaTest {
    @Test fun `passo preserva criterios padrao e customizados no task`() {
        val criteria = listOf(
            AcceptanceCriteria("build", "build passa", verification = "gradle:check"),
            AcceptanceCriteria("regression", "testes anteriores passam", verification = "test:regression")
        )
        val step = PassoPlano("build", "build", "build passa", acceptanceCriteria = criteria)
        val plan = PlanoExecucao("corrigir", listOf(step))
        assertEquals(criteria, plan.tasks.single().acceptanceCriteria)
        assertTrue(plan.tasks.single().acceptanceCriteria.all { it.description.isNotBlank() })
        assertEquals("gradle", step.structuredAcceptanceCriteria.first().verification.kind)
        assertEquals("check", step.structuredAcceptanceCriteria.first().verification.target)
    }

    @Test fun `criterios duplicados sao rejeitados`() {
        val criteria = listOf(AcceptanceCriteria("same", "um", verification = "test:a"), AcceptanceCriteria("same", "dois", verification = "test:b"))
        runCatching { PassoPlano("x", "cap", "sucesso", acceptanceCriteria = criteria) }
            .onSuccess { error("deveria rejeitar ids de critério duplicados") }
    }

    @Test fun `criterio sem metodo estruturado nao pode virar contrato`() {
        val criterion = AcceptanceCriteria("build", "build passa", verification = "sem formato")
        assertTrue(runCatching { criterion.structured() }.isFailure)
    }
}
