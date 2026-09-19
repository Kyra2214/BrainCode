package com.brain.planner

import com.brain.behavior.AcceptanceCriteria
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AcceptanceCriteriaTest {
    @Test fun `passo preserva criterios padrao e customizados no task`() {
        val criteria = listOf(
            AcceptanceCriteria("build", "build passa", verification = "gradle"),
            AcceptanceCriteria("regression", "testes anteriores passam")
        )
        val step = PassoPlano("build", "build", "build passa", acceptanceCriteria = criteria)
        val plan = PlanoExecucao("corrigir", listOf(step))
        assertEquals(criteria, plan.tasks.single().acceptanceCriteria)
        assertTrue(plan.tasks.single().acceptanceCriteria.all { it.description.isNotBlank() })
    }

    @Test fun `criterios duplicados sao rejeitados`() {
        val criteria = listOf(AcceptanceCriteria("same", "um"), AcceptanceCriteria("same", "dois"))
        runCatching { PassoPlano("x", "cap", "sucesso", acceptanceCriteria = criteria) }
            .onSuccess { error("deveria rejeitar ids de critério duplicados") }
    }
}
