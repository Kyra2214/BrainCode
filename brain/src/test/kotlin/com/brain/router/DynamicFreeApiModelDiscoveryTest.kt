package com.brain.router

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DynamicFreeApiModelDiscoveryTest {
    @Test
    fun `discovery aceita modelos novos sem allowlist e remove modelos pagos`() {
        val provider = DynamicApiProvider(
            providerId = "openrouter",
            modelsEndpoint = "https://openrouter.ai/api/v1/models",
            papers = listOf(PapelPipeline.EXECUCAO_CODIGO),
            providerFreeTier = false
        )
        val transport = ApiModelDiscoveryTransport { _, _ ->
            listOf(
                DiscoveredApiModel("modelo-novo-gratis", pricingKnown = true, inputPrice = "0", outputPrice = "0"),
                DiscoveredApiModel("modelo-pago", pricingKnown = true, inputPrice = "0.10", outputPrice = "0.20"),
                DiscoveredApiModel("modelo-inativo", active = false, pricingKnown = true, inputPrice = "0", outputPrice = "0")
            )
        }
        val discovery = DynamicFreeApiModelDiscovery(transport, { "key" })
        val models = discovery.refresh(provider)
        assertEquals(listOf("modelo-novo-gratis"), models.map { it.modeloId })
    }

    @Test
    fun `provider free tier aceita modelos descobertos mesmo sem metadado de preco`() {
        val provider = DynamicApiProvider(
            providerId = "groq",
            modelsEndpoint = "https://api.groq.com/openai/v1/models",
            papers = listOf(PapelPipeline.PLANEJAMENTO),
            providerFreeTier = true
        )
        val transport = ApiModelDiscoveryTransport { _, _ ->
            listOf(DiscoveredApiModel("modelo-que-mudou-hoje", pricingKnown = false))
        }
        val discovery = DynamicFreeApiModelDiscovery(transport, { "key" })
        assertTrue(discovery.refresh(provider).any { it.modeloId == "modelo-que-mudou-hoje" })
    }
}
