package com.brain.validation

import com.brain.secretary.Door
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ValidationEngineTest {
    private val engine = ValidationEngine()

    @Test
    fun `chat simples passa no contrato light`() {
        val result = engine.lightChat(
            ValidationSubject(
                capability = "chat.respond",
                door = Door.CHAT,
                result = "Entendi seu pedido.",
                evidence = listOf("chat:conversation", "chat:secretary:accept", "chat:request:test")
            )
        )
        assertEquals(ValidationStatus.PASS, result.status)
        assertTrue(result.failedChecks.isEmpty())
    }

    @Test
    fun `clarificacao e resultado valido needs input`() {
        val result = engine.lightChat(
            ValidationSubject(
                capability = "chat.respond",
                door = Door.CHAT,
                result = "Preciso de um esclarecimento.",
                evidence = listOf("chat:clarification-question"),
                missingRequirements = listOf("sujeito principal"),
                requiresInput = true
            )
        )
        assertEquals(ValidationStatus.NEEDS_INPUT, result.status)
        assertEquals(listOf("sujeito principal"), result.missingRequirements)
    }

    @Test
    fun `resposta vazia falha`() {
        val result = engine.lightChat(
            ValidationSubject(
                capability = "chat.respond",
                door = Door.CHAT,
                result = "",
                evidence = listOf("chat:conversation")
            )
        )
        assertEquals(ValidationStatus.FAIL, result.status)
        assertTrue("response.non-empty" in result.failedChecks)
    }
}
