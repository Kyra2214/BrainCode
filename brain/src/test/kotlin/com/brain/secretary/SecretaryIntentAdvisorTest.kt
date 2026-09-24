package com.brain.secretary

import com.brain.conversation.IntentAdvisor
import com.brain.conversation.OrderIntentSugerido
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SecretaryIntentAdvisorTest {
    @Test
    fun `colisao discutir e app chama advisor e corrige para chat`() {
        var calls = 0
        val advisor = IntentAdvisor { _, tentative ->
            calls += 1
            assertEquals(Door.CHAT, tentative.door)
            OrderIntentSugerido(Door.CHAT, intent = "DISCUSSION")
        }

        val result = DeterministicSecretary(advisor).classify("quero discutir sobre app de IPTV")

        assertEquals(Door.CHAT, result.door)
        assertEquals(CreatePhase.CHAT, result.phase)
        assertTrue(result.precisaRevisaoLLM)
        assertEquals(1, calls)
    }

    @Test
    fun `mensagem sem colisao segue deterministica sem chamar advisor`() {
        var calls = 0
        val advisor = IntentAdvisor { _, _ ->
            calls += 1
            OrderIntentSugerido(Door.CREATE)
        }

        val result = DeterministicSecretary(advisor).classify("quero criar um app de música")

        assertEquals(Door.CREATE, result.door)
        assertFalse(result.precisaRevisaoLLM)
        assertEquals(0, calls)
    }

    @Test
    fun `falha do advisor preserva classificacao deterministica`() {
        val result = DeterministicSecretary(
            IntentAdvisor { _, _ -> error("timeout") }
        ).classify("quero discutir sobre app de IPTV")

        assertEquals(Door.CHAT, result.door)
        assertTrue(result.precisaRevisaoLLM)
    }
}
