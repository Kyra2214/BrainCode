package com.brain.router

import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResilientApiCatalogTest {
    private fun model() = ProviderModel("p", "m", listOf(PapelPipeline.EXECUCAO_CODIGO), JanelaLimite(porMinuto = 2, porDia = 3))

    @Test fun `quota por minuto bloqueia terceira reserva`() {
        val catalog = ResilientApiCatalog(InMemoryApiCatalog(listOf(model())))
        assertTrue(catalog.reserve(model()))
        assertTrue(catalog.reserve(model()))
        assertFalse(catalog.reserve(model()))
        assertEquals(0, catalog.statsAtuais("p", "m")?.quotaRestanteEstimada)
    }

    @Test fun `janela de minuto reinicia`() {
        val now = AtomicReference(Instant.parse("2026-09-12T19:00:00Z"))
        val catalog = ResilientApiCatalog(InMemoryApiCatalog(listOf(model())), clock = { now.get() })
        assertTrue(catalog.reserve(model()))
        now.set(Instant.parse("2026-09-12T19:01:01Z"))
        assertTrue(catalog.reserve(model()))
    }

    @Test fun `waterfall pula modelo em cooldown`() {
        val first = model().copy(providerId = "first")
        val second = model().copy(providerId = "second")
        val catalog = ResilientApiCatalog(InMemoryApiCatalog(listOf(first, second)))
        val chosen = catalog.waterfall(PapelPipeline.EXECUCAO_CODIGO) { it.providerId == "second" }
        assertEquals("second", chosen?.providerId)
    }

    @Test fun `quota persiste entre instancias`() {
        val file = Files.createTempFile("api-quota", ".json").toFile()
        try {
            val first = ResilientApiCatalog(InMemoryApiCatalog(listOf(model())), stateFile = file)
            assertTrue(first.reserve(model()))
            val second = ResilientApiCatalog(InMemoryApiCatalog(listOf(model())), stateFile = file)
            assertEquals(1, second.statsAtuais("p", "m")?.quotaRestanteEstimada)
        } finally { file.delete() }
    }
}
