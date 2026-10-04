package com.brain.provider

import com.brain.capability.CapabilityAvailability
import com.brain.capability.CapabilityCategory
import com.brain.capability.CapabilityDefinition
import com.brain.capability.CapabilityProvenance
import com.brain.gateway.ActionGateway
import com.brain.gateway.ActionExecution
import com.brain.gateway.InMemoryActionAuditLog
import com.brain.policy.PolicyBroker
import com.brain.policy.PolicyContext
import com.brain.secretary.CreatePhase
import com.brain.secretary.Door
import com.brain.secretary.DoorScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ToolCallingLoopTest {
    private fun capability(id: String) = CapabilityDefinition(
        id = id, name = id, description = "tool de teste", category = CapabilityCategory.API,
        ownerId = "test", origin = "test", availability = CapabilityAvailability.AVAILABLE,
        provenance = listOf(CapabilityProvenance("test", "unit"))
    )

    private fun context(scope: DoorScope = DoorScope(Door.CHAT, CreatePhase.CHAT, externalAccountsAllowed = true)) =
        PolicyContext("run-tool", "task-tool", "agent", networkAllowed = true, doorScope = scope)

    @Test
    fun `catalogo filtra tools por porta e restricao`() {
        val chat = DoorToolCatalog.forScope(DoorScope(Door.CHAT, CreatePhase.CHAT))
        val noWeb = DoorToolCatalog.forScope(DoorScope(Door.CHAT, CreatePhase.CHAT, restrictions = setOf(com.brain.secretary.Restriction.NO_WEB)))
        assertTrue(chat.any { it.name == "network.research" })
        assertTrue(noWeb.none { it.name == "network.research" })
        assertTrue(chat.none { it.name == "workspace.write" })
    }

    @Test
    fun `tool call passa pelo ActionGateway e retorna ao modelo`() {
        val registry = com.brain.capability.CapabilityRegistry(listOf(capability("weather")))
        val policy = PolicyBroker(actorCapabilities = mapOf("agent" to listOf("weather"))).withCapabilityRegistry(registry)
        val audit = InMemoryActionAuditLog()
        var calls = 0
        val gateway = ActionGateway(registry, policy, { _, _, _ ->
            calls++
            ActionExecution(true, result = "22 graus", evidence = listOf("weather:test"))
        }, audit)
        var rounds = 0
        val loop = ToolCallingLoop(gateway, ToolCompletionClient { prompt, tools ->
            rounds++
            if (rounds == 1) ToolCompletionTurn(toolCalls = listOf(ProviderToolCall("c1", "weather", "{\"location\":\"Niterói\"}")))
            else {
                assertTrue(prompt.contains("22 graus"))
                assertEquals(1, tools.count { it.name == "weather" })
                ToolCompletionTurn(text = "A temperatura é 22 graus.")
            }
        })

        val result = loop.run("Qual o clima?", context())

        assertEquals("A temperatura é 22 graus.", result.text)
        assertEquals(1, calls)
        assertEquals(listOf("weather"), result.executedCalls)
        assertEquals(2, result.turns)
        assertEquals(1, audit.all().size)
    }

    @Test
    fun `tool fora da policy e bloqueada sem executar`() {
        val registry = com.brain.capability.CapabilityRegistry(listOf(capability("weather")))
        val policy = PolicyBroker(actorCapabilities = emptyMap()).withCapabilityRegistry(registry)
        val audit = InMemoryActionAuditLog()
        var calls = 0
        val gateway = ActionGateway(registry, policy, { _, _, _ -> calls++; ActionExecution(true, result = "não deveria") }, audit)
        val loop = ToolCallingLoop(gateway, ToolCompletionClient { _, _ ->
            ToolCompletionTurn(toolCalls = listOf(ProviderToolCall("c1", "weather", "{\"location\":\"Niterói\"}")))
        }, maxTurns = 1)

        val result = loop.run("Qual o clima?", context())

        assertEquals(0, calls)
        assertEquals(listOf("weather"), result.blockedCalls)
        assertTrue(result.evidence.contains("tool-loop:blocked:weather"))
    }
}
