package com.brain.behavior

import org.junit.Assert.assertEquals
import org.junit.Test

class UniversalCriticTest {
    @Test fun `critica detecta requisito ausente e pede revisao`() {
        val input = CritiqueInput("criar", listOf("foguete", "céu estrelado"), result = "foguete")
        val critique = UniversalCritic().evaluate(input)
        assertEquals(CritiqueStatus.NEEDS_REVISION, critique.status)
        assertEquals(RevisionAction.REVISE, DoubtDrivenReview().review(input, critique).action)
    }

    @Test fun `critica bloqueia resultado vazio`() {
        val input = CritiqueInput("corrigir bug", emptyList(), result = "")
        val critique = UniversalCritic().evaluate(input)
        assertEquals(CritiqueStatus.BLOCKED, critique.status)
        assertEquals(RevisionAction.ABORT, DoubtDrivenReview().review(input, critique).action)
    }

    @Test fun `alto risco exige evidencia verificada`() {
        val input = CritiqueInput("migrar", emptyList(), result = "feito", highRisk = true)
        val critique = UniversalCritic().evaluate(input)
        assertEquals(CritiqueStatus.BLOCKED, critique.status)
    }

    @Test fun `resultado completo passa`() {
        val input = CritiqueInput("prompt", listOf("foguete", "céu estrelado"), result = "foguete no céu estrelado")
        assertEquals(CritiqueStatus.PASS, UniversalCritic().evaluate(input).status)
    }
}
