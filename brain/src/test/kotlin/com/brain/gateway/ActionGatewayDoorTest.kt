package com.brain.gateway

import com.brain.capability.CapabilityAvailability
import com.brain.capability.CapabilityCategory
import com.brain.capability.CapabilityDefinition
import com.brain.capability.CapabilityProvenance
import com.brain.policy.Decision
import com.brain.policy.PolicyBroker
import com.brain.policy.PolicyContext
import com.brain.secretary.CreatePhase
import com.brain.secretary.Door
import com.brain.secretary.DoorScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ActionGatewayDoorTest {
    @Test
    fun `gateway nao executa sandbox code quando intent e chat`() {
        val definition = CapabilityDefinition(
            id = "sandbox.code", name = "sandbox.code", description = "execução", category = CapabilityCategory.SANDBOX,
            ownerId = "test", origin = "test", availability = CapabilityAvailability.AVAILABLE,
            provenance = listOf(CapabilityProvenance("test", "unit"))
        )
        val registry = com.brain.capability.CapabilityRegistry(listOf(definition))
        val policy = PolicyBroker(actorCapabilities = mapOf("agent" to listOf("sandbox.code"))).withCapabilityRegistry(registry)
        var calls = 0
        val gateway = ActionGateway(registry, policy, ActionExecutor { _, _, _ -> calls++; ActionExecution(true, result = "não deveria") }, InMemoryActionAuditLog())
        val request = ActionRequest(
            actionId = "action-chat-code", actor = "agent", capability = "sandbox.code",
            context = PolicyContext("run", "task", "agent", doorScope = DoorScope(Door.CHAT, CreatePhase.CHAT))
        )

        val result = gateway.execute(request)

        assertFalse(result.success)
        assertEquals(Decision.DENY, result.decision.decision)
        assertEquals(0, calls)
    }
}
