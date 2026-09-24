package com.brain.planner

import org.junit.Assert.assertEquals
import org.junit.Test

class ExecutionPlanContractTest {
    @Test
    fun `execution plan e alias do plano existente e declara capabilities requeridas`() {
        val plan: ExecutionPlan = PlanoExecucao(
            "criar aplicativo",
            listOf(
                PassoPlano("analisar", "analysis.requirements", "requisitos definidos"),
                PassoPlano("testar", "sandbox.test", "testes verdes", dependeDe = listOf("analisar"))
            )
        )

        assertEquals(setOf("analysis.requirements", "sandbox.test"), plan.requiredCapabilities)
        assertEquals(listOf("analisar", "testar"), plan.ordemDeExecucao.map { it.id })
    }
}
