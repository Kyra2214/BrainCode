package com.sandbox.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class P0ConversationRegressionTest {
    private fun engine() = NoInferenceConversationEngine(assets = { path -> java.io.File("src/main/assets/$path").readText() })

    @Test
    fun `olá usa resposta em português`() {
        val response = requireNotNull(engine().respond("olá"))
        assertTrue(response.text.contains("Olá", ignoreCase = true) || response.text.contains("Oi", ignoreCase = true))
        assertFalse(response.text.contains("what can I help", ignoreCase = true))
    }

    @Test
    fun `IPTV não casa com conhecimento de disjuntor por verbo genérico`() {
        val response = engine().respond("me explique como funciona IPTV")
        assertFalse(response?.text.orEmpty().contains("disjuntor", ignoreCase = true))
        assertTrue(response == null || response.intent == "knowledge.unknown")
    }

    @Test
    fun `fale sobre é pergunta informacional recuperável`() {
        assertTrue(engine().respond("fale sobre Flamengo")?.intent == "knowledge.unknown")
    }
}
