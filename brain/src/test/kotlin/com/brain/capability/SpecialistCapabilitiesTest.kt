package com.brain.capability

import com.brain.gateway.ActionExecution
import com.brain.gateway.ActionExecutor
import com.brain.gateway.ActionGateway
import com.brain.gateway.ActionRequest
import com.brain.gateway.InMemoryActionAuditLog
import com.brain.policy.PolicyBroker
import com.brain.policy.PolicyContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpecialistCapabilitiesTest {
    private val sandbox = CapabilityDefinition(
        id = "sandbox.health", name = "sandbox.health", description = "health", category = CapabilityCategory.SANDBOX,
        ownerId = "android-sandbox", origin = "android-sandbox", providedCapabilities = setOf("sandbox.health"),
        availability = CapabilityAvailability.AVAILABLE, provenance = listOf(CapabilityProvenance("test", "unit"))
    )

    @Test
    fun `cobre exatamente os especialistas de SpecialistDefinitions e cabe no registry`() {
        val defs = SpecialistCapabilities.definitions()
        assertEquals(SpecialistDefinitions.getAllSpecialists().map { it.capabilityId }, defs.map { it.id })
        assertEquals(12, defs.size)
        assertTrue(defs.all { it.category == CapabilityCategory.AGENT })
        assertTrue(defs.all { SpecialistCapabilities.isSpecialist(it) })
        CapabilityRegistry(defs) // não pode lançar por id duplicado
    }

    @Test
    fun `capabilities atribuidas pelo planner resolvem no registry`() {
        val registry = CapabilityRegistry(SpecialistCapabilities.definitions())
        listOf(
            "requirements.resolve", "architecture.design", "code.edit",
            "integration.execute", "test.plan", "release.package"
        ).forEach { capability ->
            assertTrue("sem especialista para $capability", registry.findByCapability(capability).isNotEmpty())
        }
    }

    @Test
    fun `conteudo do especialista vem de SpecialistDefinitions e policy de BuiltIn`() {
        val security = SpecialistCapabilities.definitions().single { it.id == "agent.security" }
        assertEquals("INTEGRATION", security.metadata["phase"])
        assertEquals(com.brain.execution.RiskClass.HIGH, security.risk)
        assertTrue(security.provides("security.review"))
    }

    @Test
    fun `mergeInto nao duplica id ja registrado por provider`() {
        val provider = SpecialistCapabilities.definitions().first().copy(ownerId = "plugin", description = "do plugin")
        val merged = SpecialistCapabilities.mergeInto(listOf(sandbox, provider))
        assertEquals(merged.size, merged.map { it.id }.toSet().size)
        assertEquals("do plugin", merged.single { it.id == provider.id }.description)
        CapabilityRegistry(merged)
    }

    @Test
    fun `especialistas registrados nao sao executaveis quando fora do grant`() {
        val registry = CapabilityRegistry(SpecialistCapabilities.mergeInto(listOf(sandbox)))
        val grants = SpecialistCapabilities.grantable(registry.all()).flatMap { listOf(it.id) + it.providedCapabilities }
        assertFalse("agent.code" in grants)
        assertFalse("code.edit" in grants)
        assertTrue("sandbox.health" in grants)

        val policy = PolicyBroker(grants, mapOf("actor" to grants)).withCapabilityRegistry(registry)
        var executed = 0
        val gateway = ActionGateway(registry, policy, ActionExecutor { _, _, _ -> executed++; ActionExecution(true) }, InMemoryActionAuditLog())

        val denied = gateway.execute(
            ActionRequest("a1", "actor", "agent.code", context = PolicyContext("run", "task", "actor"), provenance = listOf("unit-test"))
        )
        assertFalse(denied.success)
        assertEquals(0, executed)

        val allowed = gateway.execute(
            ActionRequest("a2", "actor", "sandbox.health", context = PolicyContext("run", "task", "actor"), provenance = listOf("unit-test"))
        )
        assertTrue(allowed.success)
        assertEquals(1, executed)
    }
}
