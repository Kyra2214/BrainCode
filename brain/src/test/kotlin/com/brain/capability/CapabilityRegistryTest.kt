package com.brain.capability

import com.brain.execution.RiskClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityRegistryTest {
    private fun provenance() = listOf(CapabilityProvenance("test", "unit-test", evidence = "fixture"))

    private fun capability(
        id: String,
        category: CapabilityCategory = CapabilityCategory.TOOL,
        risk: RiskClass = RiskClass.LOW,
        cost: CostClass = CostClass.FREE,
        available: CapabilityAvailability = CapabilityAvailability.AVAILABLE,
        provided: Set<String> = emptySet(),
        providers: Set<String> = setOf("provider.test"),
        supportsCode: Boolean = false
    ) = CapabilityDefinition(
        id = id,
        name = id,
        description = "capability de teste $id",
        category = category,
        ownerId = "owner.test",
        origin = "test-source",
        providedCapabilities = provided,
        risk = risk,
        cost = cost,
        supportsCode = supportsCode,
        availability = available,
        provenance = provenance(),
        providerIds = providers
    )

    @Test
    fun `modelo universal preserva metadados e valida proveniencia`() {
        val model = capability(
            "api.code",
            category = CapabilityCategory.API,
            risk = RiskClass.MEDIUM,
            cost = CostClass.LOW,
            provided = setOf("code_analysis"),
            supportsCode = true
        )

        assertEquals(CapabilityCategory.API, model.category)
        assertEquals(RiskClass.MEDIUM, model.risk)
        assertEquals(CostClass.LOW, model.cost)
        assertTrue(model.provides("api.code"))
        assertTrue(model.provides("code_analysis"))
        assertTrue(model.isDiscoverable())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `modelo rejeita reliability fora do intervalo`() {
        capability("invalid").copy(reliability = 1.1)
    }

    @Test
    fun `registry registra consulta por categoria capability risco custo e provider`() {
        val registry = CapabilityRegistry(
            listOf(
                capability("api.code", CapabilityCategory.API, RiskClass.MEDIUM, CostClass.LOW, provided = setOf("code_analysis"), supportsCode = true),
                capability("sandbox.test", CapabilityCategory.SANDBOX, RiskClass.LOW, CostClass.FREE, supportsCode = true),
                capability("research.agent", CapabilityCategory.AGENT, RiskClass.HIGH, CostClass.MEDIUM, providers = setOf("research.provider"))
            )
        )

        assertEquals(listOf("api.code"), registry.findByCategory(CapabilityCategory.API).map { it.id })
        assertEquals(listOf("api.code"), registry.findByCapability("code_analysis").map { it.id })
        assertEquals(listOf("sandbox.test"), registry.findByRisk(RiskClass.LOW).map { it.id })
        assertEquals(listOf("api.code", "sandbox.test"), registry.findByProvider("provider.test").map { it.id })
        assertEquals(listOf("sandbox.test"), registry.findByCost(CostClass.FREE).map { it.id })
    }

    @Test(expected = IllegalArgumentException::class)
    fun `registro rejeita duplicacao e exige update explicito`() {
        val registry = CapabilityRegistry()
        registry.register(capability("tool.one"))
        registry.register(capability("tool.one"))
    }

    @Test
    fun `update e remove alteram a mesma entrada sem criar registry paralelo`() {
        val registry = CapabilityRegistry()
        registry.register(capability("tool.one"))
        registry.update(capability("tool.one", category = CapabilityCategory.COMMAND))

        assertEquals(CapabilityCategory.COMMAND, registry.getById("tool.one")?.category)
        assertEquals("tool.one", registry.remove("tool.one")?.id)
        assertFalse(registry.all().any { it.id == "tool.one" })
    }

    @Test
    fun `discover filtra disponibilidade requisitos risco custo suporte e provider`() {
        val registry = CapabilityRegistry(
            listOf(
                capability("code.free", cost = CostClass.FREE, supportsCode = true, provided = setOf("code")),
                capability("code.paid", cost = CostClass.HIGH, supportsCode = true, provided = setOf("code")),
                capability("code.offline", available = CapabilityAvailability.UNAVAILABLE, supportsCode = true, provided = setOf("code")),
                capability("web.free", cost = CostClass.FREE, supportsCode = false, provided = setOf("web"))
            )
        )

        val result = registry.discover(
            CapabilityQuery(
                requiredCapabilities = setOf("code"),
                providerIds = setOf("provider.test"),
                riskAtMost = RiskClass.LOW,
                costAtMost = CostClass.LOW,
                supportsCode = true
            )
        )

        assertEquals(listOf("code.free"), result.map { it.id })
        assertTrue(registry.discover(CapabilityQuery(availableOnly = false)).any { it.id == "code.offline" })
    }
}
