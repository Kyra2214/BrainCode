package com.brain.gateway

import com.brain.capability.CapabilityAvailability
import com.brain.capability.CapabilityCategory
import com.brain.capability.CapabilityDefinition
import com.brain.capability.CapabilityProvenance
import com.brain.policy.Decision
import com.brain.policy.PolicyBroker
import com.brain.policy.PolicyContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionGatewayTest {
    private val capability = CapabilityDefinition(
        id = "tool.read",
        name = "Tool Read",
        description = "leitura declarativa",
        category = CapabilityCategory.TOOL,
        ownerId = "tool.owner",
        origin = "builtin",
        availability = CapabilityAvailability.AVAILABLE,
        provenance = listOf(CapabilityProvenance("test", "unit-test"))
    )

    private fun request(capability: String = "tool.read", parameters: Map<String, String> = emptyMap()) = ActionRequest(
        actionId = "action-1",
        actor = "agent",
        capability = capability,
        parameters = parameters,
        resource = "/safe/file",
        context = PolicyContext(
            runId = "run", taskId = "task", actor = "agent",
            filesystemRoots = listOf("/safe"), sandboxRequired = true
        ),
        provenance = listOf("plan:1")
    )

    @Test
    fun `gateway executa somente apos policy e registra evidencia`() {
        val registry = com.brain.capability.CapabilityRegistry(listOf(capability))
        val policy = PolicyBroker(actorCapabilities = mapOf("agent" to listOf("tool.read")))
            .withCapabilityRegistry(registry)
        val audit = InMemoryActionAuditLog()
        var calls = 0
        val gateway = ActionGateway(registry, policy, ActionExecutor { _, _, _ ->
            calls += 1
            ActionExecution(true, result = "ok", evidence = listOf("sandbox:1"), provenance = listOf("executor:test"))
        }, audit)

        val result = gateway.execute(request(parameters = mapOf("path" to "/safe/file", "api_token" to "do-not-log")))
        val record = audit.all().single()

        assertTrue(result.success)
        assertEquals(1, calls)
        assertEquals("ok", record.result)
        assertEquals("[REDACTED]", record.safeParameters["api_token"])
        assertEquals(listOf("plan:1", "executor:test"), record.provenance)
        assertTrue(record.finishedAt >= record.startedAt)
    }

    @Test
    fun `gateway nao executa capability inexistente`() {
        val registry = com.brain.capability.CapabilityRegistry(listOf(capability))
        val policy = PolicyBroker(actorCapabilities = mapOf("agent" to listOf("missing")))
            .withCapabilityRegistry(registry)
        val audit = InMemoryActionAuditLog()
        var calls = 0
        val gateway = ActionGateway(registry, policy, ActionExecutor { _, _, _ ->
            calls += 1
            ActionExecution(true)
        }, audit)

        val result = gateway.execute(request("missing"))

        assertFalse(result.success)
        assertEquals(0, calls)
        assertEquals(Decision.DENY, result.decision.decision)
        assertEquals("missing", audit.all().single().capability)
    }

    @Test
    fun `gateway nao contorna policy para actor sem capability`() {
        val registry = com.brain.capability.CapabilityRegistry(listOf(capability))
        val policy = PolicyBroker(actorCapabilities = emptyMap()).withCapabilityRegistry(registry)
        val audit = InMemoryActionAuditLog()
        var calls = 0
        val gateway = ActionGateway(registry, policy, ActionExecutor { _, _, _ ->
            calls += 1
            ActionExecution(true)
        }, audit)

        val result = gateway.execute(request())

        assertFalse(result.success)
        assertEquals(0, calls)
        assertEquals(Decision.DENY, result.decision.decision)
    }
}
