package com.brain.router

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiDiscoveryTest {
    private fun model(provider: String, name: String) = ProviderModel(
        providerId = provider,
        modeloId = name,
        papeisSugeridos = listOf(PapelPipeline.PLANEJAMENTO),
        janela = JanelaLimite(porMinuto = 10)
    )

    private fun candidate(
        provider: String,
        name: String,
        source: ApiDiscoverySource = ApiDiscoverySource("official", "Official", official = true, priority = 10),
        confidence: Double = 0.9,
        active: Boolean = true
    ) = ApiDiscoveryCandidate(model(provider, name), source, "https://example.com/$provider/$name", confidence, active = active)

    @Test
    fun `aceita candidato oficial novo e preserva ordem por prioridade`() {
        val engine = ApiDiscoveryEngine()
        val low = candidate("low", "m", source = ApiDiscoverySource("low", "Low", true, 1))
        val high = candidate("high", "m", source = ApiDiscoverySource("high", "High", true, 20))

        val report = engine.discover(emptyList(), listOf(low, high))

        assertEquals(listOf("high", "low"), report.accepted.map { it.providerId })
        assertTrue(report.reviewRequired.isEmpty())
    }

    @Test
    fun `deduplica candidatos contra catalogo e entre si`() {
        val engine = ApiDiscoveryEngine()
        val first = candidate("p", "m")
        val duplicate = candidate("P", "M")

        val report = engine.discover(listOf(model("existing", "m")), listOf(first, duplicate))

        assertEquals(1, report.accepted.size)
        assertEquals(ApiDiscoveryDecision.REJECT, report.findings[1].decision)
    }

    @Test
    fun `fonte nao oficial e baixa confianca exigem revisao`() {
        val engine = ApiDiscoveryEngine(minimumConfidence = 0.8)
        val untrusted = candidate(
            "p", "m",
            source = ApiDiscoverySource("community", "Community", official = false, priority = 2),
            confidence = 0.9
        )
        val uncertain = candidate("q", "m", confidence = 0.4)

        val report = engine.discover(emptyList(), listOf(untrusted, uncertain))

        assertEquals(2, report.reviewRequired.size)
        assertTrue(report.accepted.isEmpty())
    }

    @Test
    fun `modelo inativo e rejeitado`() {
        val report = ApiDiscoveryEngine().discover(emptyList(), listOf(candidate("p", "m", active = false)))
        assertEquals(ApiDiscoveryDecision.REJECT, report.findings.single().decision)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `url insegura nao entra como candidato`() {
        ApiDiscoveryCandidate(model("p", "m"), ApiDiscoverySource("s", "Source", true, 1), "http://example.com", 0.9)
    }
}
