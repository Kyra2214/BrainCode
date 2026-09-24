package com.brain.router

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cobre o "critério de pronto" da Etapa 5 do plano de integração original
 * (docs/PLANO_INTEGRACAO_BRAIN_SANDBOX.md, consolidado em docs/ARQUITETURA_ATUAL.md
 * e docs/LEGADO_E_DECISOES.md; não existe mais como arquivo separado):
 * o Router recebe a capacidade/papel e escolhe provider/modelo por
 * qualidade, confiabilidade (histórico via LiveStats), velocidade e
 * fallback — sem o usuário participar da escolha.
 *
 * Inspirado nos casos de reference/braincode-python/tests/test_apis.py e
 * tests/test_research_routing_hardening.py (test_router_selects_best_equivalent_provider),
 * adaptados ao desenho atual (RoutingProfile/LiveStats), não a
 * DynamicApiCatalog — ver limitações conscientes documentadas em AIRouter.kt.
 */
class DefaultAIRouterTest {

    private fun modelo(
        providerId: String,
        modeloId: String = "m",
        papeis: List<PapelPipeline> = listOf(PapelPipeline.EXECUCAO_CODIGO)
    ) = ProviderModel(providerId, modeloId, papeis, JanelaLimite())

    @Test
    fun `sem candidatos para o papel retorna null`() {
        val catalog = InMemoryApiCatalog(emptyList())
        val decisao = DefaultAIRouter().decidir(PapelPipeline.EXECUCAO_CODIGO, catalog, emptyList())
        assertNull(decisao)
    }

    @Test
    fun `escolhe o provider com melhor qualidade quando nao ha historico`() {
        val bom = modelo("good")
        val fraco = modelo("cheap")
        val catalog = InMemoryApiCatalog(listOf(bom, fraco))
        val profiles = listOf(
            RoutingProfile("good", "m", qualityScore = .95),
            RoutingProfile("cheap", "m", qualityScore = .4)
        )

        val decisao = DefaultAIRouter().decidir(PapelPipeline.EXECUCAO_CODIGO, catalog, profiles)

        assertEquals("good", decisao?.escolhido?.providerId)
        assertEquals(listOf("cheap"), decisao?.alternativas?.map { it.providerId })
    }

    @Test
    fun `historico de sucesso pesa mais que o qualityScore inicial`() {
        val historicoRuim = modelo("a")
        val historicoBom = modelo("b")
        val catalog = InMemoryApiCatalog(listOf(historicoRuim, historicoBom))
        // "a" tem qualityScore maior no profile, mas falhou muito -> "b" deve vencer.
        repeat(5) { catalog.registrarResultado("a", "m", sucesso = false, latenciaMs = 500) }
        repeat(5) { catalog.registrarResultado("b", "m", sucesso = true, latenciaMs = 500) }
        val profiles = listOf(
            RoutingProfile("a", "m", qualityScore = .9),
            RoutingProfile("b", "m", qualityScore = .6)
        )

        val decisao = DefaultAIRouter().decidir(PapelPipeline.EXECUCAO_CODIGO, catalog, profiles)

        assertEquals("b", decisao?.escolhido?.providerId)
    }

    @Test
    fun `chave invalida so e escolhida se nao sobrar alternativa`() {
        val comChaveInvalida = modelo("only")
        val catalog = InMemoryApiCatalog(listOf(comChaveInvalida))
        catalog.registrarResultado(
            "only", "m", sucesso = false, latenciaMs = 100,
            erro = ErroObservado(TipoErro.CHAVE_INVALIDA, java.time.Instant.now())
        )

        val decisao = DefaultAIRouter().decidir(PapelPipeline.EXECUCAO_CODIGO, catalog, emptyList())

        assertEquals("only", decisao?.escolhido?.providerId)
        assertTrue(decisao!!.motivo.contains("ATENÇÃO"))
    }

    @Test
    fun `chave invalida perde para alternativa saudavel`() {
        val invalido = modelo("bad-key")
        val saudavel = modelo("ok")
        val catalog = InMemoryApiCatalog(listOf(invalido, saudavel))
        catalog.registrarResultado(
            "bad-key", "m", sucesso = false, latenciaMs = 100,
            erro = ErroObservado(TipoErro.CHAVE_INVALIDA, java.time.Instant.now())
        )

        val decisao = DefaultAIRouter().decidir(PapelPipeline.EXECUCAO_CODIGO, catalog, emptyList())

        assertEquals("ok", decisao?.escolhido?.providerId)
    }

    @Test
    fun `proximaAlternativa devolve o proximo da lista de fallback`() {
        val catalog = InMemoryApiCatalog(listOf(modelo("a"), modelo("b"), modelo("c")))
        val profiles = listOf(
            RoutingProfile("a", "m", qualityScore = .9),
            RoutingProfile("b", "m", qualityScore = .8),
            RoutingProfile("c", "m", qualityScore = .7)
        )
        val router = DefaultAIRouter()
        val decisao = router.decidir(PapelPipeline.EXECUCAO_CODIGO, catalog, profiles)!!

        val proxima = router.proximaAlternativa(decisao)

        assertEquals("b", proxima?.providerId)
    }

    @Test
    fun `filtra candidatos que nao servem o papel pedido`() {
        val planejamento = modelo("planner-api", papeis = listOf(PapelPipeline.PLANEJAMENTO))
        val execucao = modelo("coder-api", papeis = listOf(PapelPipeline.EXECUCAO_CODIGO))
        val catalog = InMemoryApiCatalog(listOf(planejamento, execucao))

        val decisao = DefaultAIRouter().decidir(PapelPipeline.EXECUCAO_CODIGO, catalog, emptyList())

        assertEquals("coder-api", decisao?.escolhido?.providerId)
    }
}
