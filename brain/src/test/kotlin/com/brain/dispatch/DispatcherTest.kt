package com.brain.dispatch

import com.brain.capability.CapabilityAvailability
import com.brain.capability.CapabilityCategory
import com.brain.capability.CapabilityDefinition
import com.brain.capability.CapabilityDiscovery
import com.brain.capability.CapabilityProvenance
import com.brain.capability.CapabilityRegistry
import com.brain.gateway.ActionExecution
import com.brain.gateway.ActionGateway
import com.brain.gateway.InMemoryActionAuditLog
import com.brain.policy.PolicyBroker
import com.brain.policy.PolicyContext
import com.brain.planner.PassoPlano
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DispatcherTest {
    private fun definition(id: String, provides: Set<String>) = CapabilityDefinition(
        id = id,
        name = id,
        description = "capability de teste",
        category = CapabilityCategory.TOOL,
        ownerId = "tool.owner",
        origin = "builtin",
        providedCapabilities = provides,
        availability = CapabilityAvailability.AVAILABLE,
        provenance = listOf(CapabilityProvenance("test", "unit-test"))
    )

    private fun context() = PolicyContext(
        runId = "run", taskId = "task", actor = "brain",
        sandboxRequired = true, filesystemRoots = listOf("/safe")
    )

    @Test
    fun `dispatcher seleciona candidato e entrega ao gateway`() {
        val registry = CapabilityRegistry(listOf(definition("tool.read", setOf("read"))))
        val policy = PolicyBroker(actorCapabilities = mapOf("brain" to listOf("tool.read")))
            .withCapabilityRegistry(registry)
        val audit = InMemoryActionAuditLog()
        val gateway = ActionGateway(registry, policy, { _, _, _ -> ActionExecution(true, "done") }, audit)
        val dispatcher = Dispatcher(CapabilityDiscovery(registry), gateway)

        val result = dispatcher.dispatch(
            DispatchTask("task", PassoPlano("step", "read", "ler arquivo", listOf("/safe/a")), "brain", context())
        )

        assertEquals(DispatchStatus.DISPATCHED, result.status)
        assertEquals("tool.read", result.selected?.capability?.id)
        assertEquals("task:step", audit.all().single().actionId)
        assertTrue(result.gateway?.success == true)
    }

    @Test
    fun `dispatcher faz fallback para proximo candidato de capability quando o primeiro falha`() {
        val registry = CapabilityRegistry(
            listOf(definition("tool.a", setOf("read")), definition("tool.b", setOf("read")))
        )
        val policy = PolicyBroker(actorCapabilities = mapOf("brain" to listOf("tool.a", "tool.b")))
            .withCapabilityRegistry(registry)
        val audit = InMemoryActionAuditLog()
        val tentadas = mutableListOf<String>()
        val gateway = ActionGateway(registry, policy, { request, _, _ ->
            tentadas += request.capability
            if (request.capability == "tool.a") ActionExecution(false, error = "429 rate limit")
            else ActionExecution(true, "done")
        }, audit)
        val dispatcher = Dispatcher(CapabilityDiscovery(registry), gateway)

        val result = dispatcher.dispatch(
            DispatchTask(
                "task", PassoPlano("step", "read", "ler arquivo", listOf("/safe/a")), "brain", context(),
                maxCandidates = 2
            )
        )

        assertEquals(DispatchStatus.DISPATCHED, result.status)
        assertEquals(listOf("tool.a", "tool.b"), tentadas)
        assertEquals(2, result.attempts.size)
        assertTrue(result.gateway?.success == true)
    }

    @Test
    fun `dispatcher gira entre contas alternativas antes de desistir do mesmo candidato`() {
        val registry = CapabilityRegistry(listOf(definition("tool.read", setOf("read"))))
        val policy = PolicyBroker(actorCapabilities = mapOf("brain" to listOf("tool.read")))
            .withCapabilityRegistry(registry)
        val audit = InMemoryActionAuditLog()
        val contasTentadas = mutableListOf<String?>()
        val gateway = ActionGateway(registry, policy, { request, _, _ ->
            contasTentadas += request.accountId
            if (request.accountId == "conta-1") ActionExecution(false, error = "401 invalid api key")
            else ActionExecution(true, "done")
        }, audit)
        val dispatcher = Dispatcher(CapabilityDiscovery(registry), gateway)

        val result = dispatcher.dispatch(
            DispatchTask(
                "task", PassoPlano("step", "read", "ler arquivo", listOf("/safe/a")), "brain", context(),
                accountId = "conta-1",
                accountAlternatives = listOf("conta-2")
            )
        )

        assertEquals(DispatchStatus.DISPATCHED, result.status)
        assertEquals(listOf("conta-1", "conta-2"), contasTentadas)
        assertEquals(2, result.attempts.size)
    }

    @Test
    fun `dispatcher nao gira conta quando a falha e de policy ou requisicao invalida`() {
        val registry = CapabilityRegistry(listOf(definition("tool.read", setOf("read"))))
        val policy = PolicyBroker(actorCapabilities = mapOf("brain" to listOf("tool.read")))
            .withCapabilityRegistry(registry)
        val audit = InMemoryActionAuditLog()
        val contasTentadas = mutableListOf<String?>()
        val gateway = ActionGateway(registry, policy, { request, _, _ ->
            contasTentadas += request.accountId
            ActionExecution(false, error = "400 invalid request")
        }, audit)
        val dispatcher = Dispatcher(CapabilityDiscovery(registry), gateway)

        val result = dispatcher.dispatch(
            DispatchTask(
                "task", PassoPlano("step", "read", "ler arquivo", listOf("/safe/a")), "brain", context(),
                accountId = "conta-1",
                accountAlternatives = listOf("conta-2")
            )
        )

        assertEquals(DispatchStatus.REJECTED, result.status)
        assertEquals(listOf("conta-1"), contasTentadas)
        assertEquals(1, result.attempts.size)
    }

    @Test
    fun `dispatcher nao executa quando capability nao foi descoberta`() {
        val registry = CapabilityRegistry(listOf(definition("tool.other", setOf("other"))))
        val policy = PolicyBroker(actorCapabilities = mapOf("brain" to listOf("tool.other")))
            .withCapabilityRegistry(registry)
        val audit = InMemoryActionAuditLog()
        val gateway = ActionGateway(registry, policy, { _, _, _ -> ActionExecution(true, "unexpected") }, audit)
        val dispatcher = Dispatcher(CapabilityDiscovery(registry), gateway)

        val result = dispatcher.dispatch(
            DispatchTask("task", PassoPlano("step", "read", "ler arquivo"), "brain", context())
        )

        assertEquals(DispatchStatus.NO_CANDIDATE, result.status)
        assertTrue(audit.all().isEmpty())
    }
}
