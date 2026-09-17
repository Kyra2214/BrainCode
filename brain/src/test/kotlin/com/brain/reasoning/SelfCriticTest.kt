package com.brain.reasoning

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfCriticTest {
    private val engine = ReasoningEngine()
    private val critic = SelfCritic()

    @Test
    fun `critica reprova requisito ambiental que desapareceu`() {
        val state = engine.analyze("crie uma fotografia de um foguete com céu estrelado ao fundo")
        val result = critic.evaluate(state, "Fotografia de um foguete, ambientado em fundo genérico. Composição: equilibrada.")

        assertTrue(result.missingRequirements.contains("céu estrelado ao fundo"))
        assertFalse(result.accepted)
    }

    @Test
    fun `critica aceita requisitos concretos presentes`() {
        val state = engine.analyze("crie uma fotografia de um foguete no deserto com meteoros caindo")
        val result = critic.evaluate(state, "Fotografia de um foguete, ambientado em deserto. Vários meteoros caindo cruzam o céu.")

        assertTrue(result.attendedRequirements.contains("deserto"))
        assertTrue(result.attendedRequirements.contains("meteoros caindo"))
    }
}
