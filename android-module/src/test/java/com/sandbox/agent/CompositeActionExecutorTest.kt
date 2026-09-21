package com.sandbox.agent

import com.brain.capability.CapabilityAvailability
import com.brain.capability.CapabilityCategory
import com.brain.capability.CapabilityDefinition
import com.brain.capability.CapabilityProvenance
import com.brain.execution.RiskClass
import com.brain.gateway.ActionExecution
import com.brain.gateway.ActionExecutor
import com.brain.gateway.ActionRequest
import com.brain.policy.PolicyBroker
import com.brain.policy.PolicyContext
import org.junit.Assert.assertEquals
import org.junit.Test

class CompositeActionExecutorTest {
    @Test
    fun `usa override pela id da capability e fallback nas demais`() {
        val override = ActionExecutor { _, _, _ -> ActionExecution(true, result = "override") }
        val fallback = ActionExecutor { _, _, _ -> ActionExecution(true, result = "fallback") }
        val composite = CompositeActionExecutor(mapOf("workspace.generate" to override), fallback)
        val capability = definition("workspace.generate")
        val decision = PolicyBroker(
            allowedCapabilities = listOf("workspace.generate"),
            actorCapabilities = mapOf("agent" to listOf("workspace.generate"))
        ).authorize("agent", "workspace.generate", "", PolicyContext("run", "task", "agent", RiskClass.LOW, sandboxRequired = true))
        val request = ActionRequest("action", "agent", "workspace.generate", context = PolicyContext("run", "task", "agent", RiskClass.LOW, sandboxRequired = true))

        assertEquals("override", composite.execute(request, capability, decision).result)
        assertEquals("fallback", composite.execute(request.copy(capability = "sandbox.build"), definition("sandbox.build"), decision).result)
    }

    private fun definition(id: String) = CapabilityDefinition(
        id = id,
        name = id,
        description = "test capability",
        category = CapabilityCategory.SANDBOX,
        ownerId = "test",
        origin = "test",
        availability = CapabilityAvailability.AVAILABLE,
        provenance = listOf(CapabilityProvenance("test", "unit"))
    )
    @Test
    fun `agent sem executor dedicado falha fechado`() {
        val executor = CompositeActionExecutor(emptyMap(), ActionExecutor { _, _, _ ->
            ActionExecution(true, result = "fallback")
        })
        val capability = com.brain.capability.CapabilityDefinition(
            id = "agent.fake",
            name = "FakeAgent",
            description = "teste",
            category = com.brain.capability.CapabilityCategory.AGENT,
            ownerId = "test",
            origin = "test",
            providedCapabilities = emptySet()
        )
        val result = executor.execute(
            com.brain.gateway.ActionRequest("test", "agent.fake", emptyMap(), "test"),
            capability,
            com.brain.policy.PolicyDecision.allow("test")
        )
        assertEquals(false, result.success)
        assertTrue(result.error.orEmpty().contains("executor dedicado"))
    }

}
