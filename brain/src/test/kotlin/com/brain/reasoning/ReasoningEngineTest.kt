package com.brain.reasoning

import com.brain.prompt.PromptDomain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReasoningEngineTest {
    private val engine = ReasoningEngine()

    @Test
    fun `pedido de interface com palavra prompt e classificado como codigo`() {
        val state = engine.analyze("crie um prompt pro meu app chatbox com interface idêntica à do chatgpt")
        assertEquals(ReasoningIntent.BUILD_CODE, state.intent)
        assertEquals(PromptDomain.CODIGO, state.domain)
        assertTrue(state.requirements.any { it.text == "interface/aplicativo" })
    }

    @Test
    fun `requisitos concretos de imagem ficam explicitos`() {
        val state = engine.analyze("mude o fundo para deserto e adicione vários meteoros caindo")
        assertEquals(ReasoningIntent.REFINE_PROMPT, state.intent)
        assertTrue(state.requirements.any { it.text == "deserto" })
        assertTrue(state.requirements.any { it.text == "meteoros caindo" })
        assertTrue(state.canProceedLocally)
    }

    @Test
    fun `melhoria profissional e distinta de ajuste concreto`() {
        assertEquals(ReasoningIntent.IMPROVE_PROMPT, engine.analyze("melhore profissionalmente este prompt").intent)
        assertEquals(ReasoningIntent.REFINE_PROMPT, engine.analyze("troque o fundo para deserto").intent)
    }
}
