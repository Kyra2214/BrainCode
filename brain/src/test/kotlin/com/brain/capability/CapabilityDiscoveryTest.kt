package com.brain.capability

import com.brain.execution.RiskClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityDiscoveryTest {
    private val provenance = listOf(CapabilityProvenance("test", "unit-test"))

    private fun capability(
        id: String,
        category: CapabilityCategory,
        quality: Double,
        reliability: Double,
        required: String,
        supportsWeb: Boolean = false,
        supportsCode: Boolean = false,
        risk: RiskClass = RiskClass.LOW,
        provider: String = "provider.test"
    ) = CapabilityDefinition(
        id = id,
        name = id,
        description = "teste",
        category = category,
        ownerId = provider,
        origin = provider,
        providedCapabilities = setOf(required),
        quality = quality,
        reliability = reliability,
        supportsWeb = supportsWeb,
        supportsCode = supportsCode,
        risk = risk,
        availability = CapabilityAvailability.AVAILABLE,
        provenance = provenance,
        providerIds = setOf(provider)
    )

    @Test
    fun `discovery filtra por policy e ranqueia por qualidade e confiabilidade`() {
        val registry = CapabilityRegistry(
            listOf(
                capability("api.good", CapabilityCategory.API, .95, .95, "web_search", supportsWeb = true),
                capability("api.bad", CapabilityCategory.API, .10, .10, "web_search", supportsWeb = true),
                capability("sandbox.other", CapabilityCategory.SANDBOX, .99, .99, "code_test", supportsCode = true)
            )
        )
        val result = CapabilityDiscovery(registry).discover(
            CapabilityIntent("pesquisar no web", requiredCapabilities = setOf("web_search"), supportsWeb = true),
            policyAllows = { it.id != "api.bad" }
        )

        assertEquals(2, result.considered)
        assertEquals(1, result.policyRejected)
        assertEquals(listOf("api.good"), result.candidates.map { it.capability.id })
    }

    @Test
    fun `discovery usa categoria explicita e limite de candidatos`() {
        val registry = CapabilityRegistry(
            listOf(
                capability("code.one", CapabilityCategory.SANDBOX, .9, .8, "code_test", supportsCode = true),
                capability("code.two", CapabilityCategory.SANDBOX, .8, .9, "code_test", supportsCode = true),
                capability("agent.one", CapabilityCategory.AGENT, .99, .99, "code_test", supportsCode = true)
            )
        )
        val result = CapabilityDiscovery(registry).discover(
            CapabilityIntent(
                "validar código",
                requiredCapabilities = setOf("code_test"),
                categories = setOf(CapabilityCategory.SANDBOX),
                supportsCode = true,
                maxCandidates = 1
            )
        )

        assertEquals(setOf(CapabilityCategory.SANDBOX), result.categories)
        assertEquals(listOf("code.one"), result.candidates.map { it.capability.id })
    }

    @Test
    fun `discovery infere categorias sem carregar candidatos irrelevantes`() {
        val registry = CapabilityRegistry(
            listOf(
                capability("api.github", CapabilityCategory.API, .8, .8, "github_read", supportsWeb = true),
                capability("tool.files", CapabilityCategory.TOOL, .8, .8, "file_read"),
                capability("internal.other", CapabilityCategory.INTERNAL, .8, .8, "other")
            )
        )
        val result = CapabilityDiscovery(registry).discover(
            CapabilityIntent("pesquisar no GitHub", requiredCapabilities = setOf("github_read"), supportsWeb = true)
        )

        assertTrue(CapabilityCategory.API in result.categories)
        assertEquals(listOf("api.github"), result.candidates.map { it.capability.id })
    }
}
