package com.sandbox.app

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponseComposerTest {
    private val clock = Clock.fixed(Instant.parse("2026-09-21T18:30:00Z"), ZoneId.of("UTC"))

    @Test
    fun `compõe clarificação objetiva sem texto de planejamento`() {
        val result = ResponseComposer(clock).compose("Qual ação devo executar?", clarification = true)

        assertTrue(result.text.startsWith("Preciso de um esclarecimento"))
        assertTrue(result.evidence.contains("chat:clarification-question"))
        assertFalse(result.text.contains("organizar a ideia, os requisitos, as decisões e as pendências"))
    }

    @Test
    fun `compõe data e não confunde clima com data`() {
        val composer = ResponseComposer(clock)
        val date = composer.compose("Que dia é hoje?")
        val weather = composer.compose("tempo hoje em rio das ostras")

        assertTrue(date.text.contains("21/09/2026"))
        assertTrue(date.evidence.any { it.startsWith("chat:clock:") })
        assertFalse(weather.text.startsWith("Hoje é"))
        assertTrue(weather.evidence.contains("chat:conversation:local-miss"))
    }

    @Test
    fun `compõe contexto somente leitura`() {
        val result = ResponseComposer(clock).compose(
            "Organize o que já sabemos",
            context = ConversationContext(idea = "app de notas", requirements = listOf("offline"))
        )

        assertEquals(true, result.text.contains("app de notas"))
        assertTrue(result.evidence.contains("chat:context:read-only"))
    }

    @Test
    fun `sintese meteorologica descarta cookie menu e idioma e prioriza dado concreto`() {
        val raw = "Aceitar cookies | Português | English | Login. Macaé Weather Forecast. " +
            "Em Macaé, a temperatura é de 28°C e o céu está parcialmente nublado. " +
            "Assine nossa newsletter."

        val result = ResponseComposer(clock).compose("previsão do tempo em Macaé", research = raw)

        assertTrue(result.text.contains("28°C"))
        assertTrue(result.text.contains("nublado", ignoreCase = true))
        assertFalse(result.text.contains("cookie", ignoreCase = true))
        assertFalse(result.text.contains("login", ignoreCase = true))
        assertFalse(result.text.contains("English", ignoreCase = true))
    }
}
