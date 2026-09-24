package com.sandbox.app

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationEngineTest {
    private fun engine(): NoInferenceConversationEngine = NoInferenceConversationEngine(assets = { path ->
        File("src/main/assets/$path").readText()
    })

    @Test
    fun `social em portugues nao cai no fallback de requisitos`() {
        val response = engine().respond("olá boa noite")
        assertNotNull(response)
        assertTrue(response!!.intent.startsWith("social."))
        assertFalse(response.text.contains("requisitos", ignoreCase = true))
        assertFalse(response.text.contains("organizar a ideia", ignoreCase = true))
    }

    @Test
    fun `social cobre despedida agradecimento identidade e estado`() {
        val conversation = engine()
        listOf("oi", "bom dia", "boa tarde", "tchau", "obrigado", "valeu", "como você está?", "quem é você?").forEach { prompt ->
            val response = conversation.respond(prompt)
            assertNotNull(prompt, response)
            assertFalse(prompt, response!!.text.contains("organizar a ideia", ignoreCase = true))
        }
    }

    @Test
    fun `knowledge local responde classes informativas`() {
        val conversation = engine()
        listOf(
            "o que é um disjuntor?",
            "explique como funciona um motor elétrico",
            "quem inventou o telefone?",
            "o que significa DNS?",
            "me explique o que é inteligência artificial"
        ).forEach { prompt ->
            val response = conversation.respond(prompt)
            assertNotNull(prompt, response)
            assertTrue(prompt, response!!.intent == "knowledge.lookup")
            assertFalse(prompt, response.text.contains("organizar a ideia", ignoreCase = true))
        }
    }

    @Test
    fun `follow up reutiliza o ultimo topico sem duplicar memoria do BrainCode`() {
        val conversation = engine()
        val first = conversation.respond("o que é DNS?")
        val second = conversation.respond("explique melhor")
        assertNotNull(first)
        assertNotNull(second)
        assertTrue(second!!.intent == "follow-up.context")
        assertTrue(second.evidence.contains("no-inference:follow-up"))
    }

    @Test
    fun `unknown tem fallback conversacional neutro no composer`() {
        val composer = ResponseComposer(conversationEngine = engine())
        val response = composer.compose("uma frase sem intenção clara")
        assertTrue(response.text.contains("Não tenho conhecimento suficiente", ignoreCase = true))
        assertFalse(response.text.contains("requisitos", ignoreCase = true))
    }

    @Test
    fun `benchmark local de conversa deterministica`() {
        val conversation = engine()
        val started = System.nanoTime()
        repeat(200) { conversation.respond("o que é DNS?") }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("conversation engine levou ${elapsedMs}ms", elapsedMs < 1_000)
    }
}
