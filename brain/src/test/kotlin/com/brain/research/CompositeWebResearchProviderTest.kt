package com.brain.research

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompositeWebResearchProviderTest {

    private fun resultado(source: String) = ResearchResult(
        query = "q", source = source, title = "t", url = "https://$source/x",
        relevantContent = "conteúdo de $source", retrievedAt = Instant.now()
    )

    @Test fun `primeira fonte com resultados nao chama a segunda`() {
        var chamouSegunda = false
        val primeira = WebResearchProvider { _, _ -> Result.success(listOf(resultado("primeira"))) }
        val segunda = WebResearchProvider { _, _ -> chamouSegunda = true; Result.success(listOf(resultado("segunda"))) }

        val resultados = CompositeWebResearchProvider(listOf(primeira, segunda)).pesquisar("x").getOrThrow()

        assertEquals("primeira", resultados.single().source)
        assertTrue("não deveria ter chamado o fallback quando a primeira fonte já respondeu", !chamouSegunda)
    }

    @Test fun `fonte que falha cai para a proxima`() {
        val primeira = WebResearchProvider { _, _ -> Result.failure(java.io.IOException("HTML mudou, parsing falhou")) }
        val segunda = WebResearchProvider { _, _ -> Result.success(listOf(resultado("segunda"))) }

        val resultados = CompositeWebResearchProvider(listOf(primeira, segunda)).pesquisar("x").getOrThrow()

        assertEquals("segunda", resultados.single().source)
    }

    @Test fun `fonte que retorna vazio tambem cai para a proxima`() {
        val primeira = WebResearchProvider { _, _ -> Result.success(emptyList()) }
        val segunda = WebResearchProvider { _, _ -> Result.success(listOf(resultado("segunda"))) }

        val resultados = CompositeWebResearchProvider(listOf(primeira, segunda)).pesquisar("x").getOrThrow()

        assertEquals("segunda", resultados.single().source)
    }

    @Test fun `todas as fontes falhando propaga falha explicita`() {
        val primeira = WebResearchProvider { _, _ -> Result.failure(java.io.IOException("timeout primeira")) }
        val segunda = WebResearchProvider { _, _ -> Result.failure(java.io.IOException("timeout segunda")) }

        val outcome = CompositeWebResearchProvider(listOf(primeira, segunda)).pesquisar("x")

        assertTrue(outcome.isFailure)
        assertEquals("timeout segunda", outcome.exceptionOrNull()?.message)
    }
}
