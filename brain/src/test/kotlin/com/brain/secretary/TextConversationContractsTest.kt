package com.brain.secretary

import org.junit.Assert.assertEquals
import org.junit.Test

class TextConversationContractsTest {
    private val gate = DeterministicSecretaryGate()

    @Test
    fun `local answer is accepted`() {
        val result = gate.evaluate(
            ConversationResult("Um disjuntor interrompe o circuito quando há sobrecorrente.", ConversationStatus.ANSWERED_LOCAL),
            recoveryAvailable = false
        )
        assertEquals(SecretaryDecision.ACCEPT, result.decision)
    }

    @Test
    fun `local miss blocks when recovery is available`() {
        val result = gate.evaluate(
            ConversationResult("Não reconheci uma resposta local confiável.", ConversationStatus.LOCAL_KNOWLEDGE_MISS),
            recoveryAvailable = true
        )
        assertEquals(SecretaryDecision.BLOCK, result.decision)
        assertEquals(BlockReason.LOCAL_KNOWLEDGE_MISS, result.reason)
    }

    @Test
    fun `raw research result is not user facing`() {
        val result = gate.evaluate(
            ConversationResult("snippet 1\nFontes consultadas...", ConversationStatus.RESEARCH_RESULT_RECEIVED, listOf("chat:websearch:executed")),
            recoveryAvailable = false
        )
        assertEquals(SecretaryDecision.BLOCK, result.decision)
        assertEquals(BlockReason.INCOMPLETE_RESPONSE, result.reason)
    }

    @Test
    fun `synthesis requires evidence before accept`() {
        val result = gate.evaluate(
            ConversationResult("Kotlin é uma linguagem de programação.", ConversationStatus.ANSWER_READY, listOf("chat:websearch:evidence")),
            recoveryAvailable = false
        )
        assertEquals(SecretaryDecision.ACCEPT, result.decision)
    }

    @Test
    fun `local miss sem tentativa bloqueia mesmo com resposta de fallback`() {
        val result = gate.evaluate(
            ConversationResult(
                "Não tenho conhecimento suficiente para responder.",
                ConversationStatus.ANSWERED_LOCAL,
                evidence = listOf("chat:conversation:local-miss"),
                prompt = "Tecnologia usada no Android moderno"
            ),
            recoveryAvailable = true
        )
        assertEquals(SecretaryDecision.BLOCK, result.decision)
        assertEquals(BlockReason.LOCAL_KNOWLEDGE_MISS, result.reason)
    }

    @Test
    fun `local miss honesto só pode passar após tentativa registrada`() {
        val result = gate.evaluate(
            ConversationResult(
                "Não encontrei fontes confiáveis suficientes para responder agora.",
                ConversationStatus.ANSWER_READY,
                evidence = listOf("chat:conversation:synthesis"),
                prompt = "Tecnologia usada no Android moderno",
                researchAttempted = true
            ),
            recoveryAvailable = true
        )
        assertEquals(SecretaryDecision.ACCEPT, result.decision)
    }
}
