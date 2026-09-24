package com.brain.router

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class InMemoryApiCatalogTest {

    private fun modelo(providerId: String, papeis: List<PapelPipeline> = listOf(PapelPipeline.EXECUCAO_CODIGO)) =
        ProviderModel(providerId, "m", papeis, JanelaLimite())

    @Test
    fun `sem chamadas registradas statsAtuais e nulo`() {
        val catalog = InMemoryApiCatalog(listOf(modelo("a")))
        assertNull(catalog.statsAtuais("a", "m"))
    }

    @Test
    fun `taxa de sucesso e latencia media agregam corretamente`() {
        val catalog = InMemoryApiCatalog(listOf(modelo("a")))
        catalog.registrarResultado("a", "m", sucesso = true, latenciaMs = 100)
        catalog.registrarResultado("a", "m", sucesso = true, latenciaMs = 200)
        catalog.registrarResultado("a", "m", sucesso = false, latenciaMs = 300)

        val stats = catalog.statsAtuais("a", "m")

        assertEquals(2.0 / 3.0, stats?.taxaSucessoRecente!!, 1e-9)
        assertEquals(200L, stats.latenciaMediaMs)
    }

    @Test
    fun `sucesso nao limpa o ultimo erro registrado`() {
        val catalog = InMemoryApiCatalog(listOf(modelo("a")))
        catalog.registrarResultado(
            "a", "m", sucesso = false, latenciaMs = 100,
            erro = ErroObservado(TipoErro.LIMITE_ATINGIDO, Instant.now())
        )
        catalog.registrarResultado("a", "m", sucesso = true, latenciaMs = 50)

        val stats = catalog.statsAtuais("a", "m")

        assertEquals(TipoErro.LIMITE_ATINGIDO, stats?.ultimoErro?.tipo)
    }

    @Test
    fun `erro novo substitui o anterior`() {
        val catalog = InMemoryApiCatalog(listOf(modelo("a")))
        catalog.registrarResultado(
            "a", "m", sucesso = false, latenciaMs = 100,
            erro = ErroObservado(TipoErro.TIMEOUT, Instant.now())
        )
        catalog.registrarResultado(
            "a", "m", sucesso = false, latenciaMs = 100,
            erro = ErroObservado(TipoErro.ERRO_SERVIDOR, Instant.now())
        )

        assertEquals(TipoErro.ERRO_SERVIDOR, catalog.statsAtuais("a", "m")?.ultimoErro?.tipo)
    }

    @Test
    fun `listarPorPapel filtra pelos papeis sugeridos do modelo`() {
        val catalog = InMemoryApiCatalog(
            listOf(
                modelo("a", papeis = listOf(PapelPipeline.PLANEJAMENTO)),
                modelo("b", papeis = listOf(PapelPipeline.EXECUCAO_CODIGO))
            )
        )

        val resultado = catalog.listarPorPapel(PapelPipeline.EXECUCAO_CODIGO)

        assertEquals(listOf("b"), resultado.map { it.providerId })
    }

    @Test
    fun `listarModelos devolve todos independente do papel`() {
        val catalog = InMemoryApiCatalog(listOf(modelo("a"), modelo("b")))
        assertTrue(catalog.listarModelos().map { it.providerId }.containsAll(listOf("a", "b")))
    }
}
