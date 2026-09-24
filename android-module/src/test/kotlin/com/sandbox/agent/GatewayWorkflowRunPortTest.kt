package com.sandbox.agent

import com.brain.capability.CapabilityAvailability
import com.brain.capability.CapabilityCategory
import com.brain.capability.CapabilityDefinition
import com.brain.capability.CapabilityProvenance
import com.brain.capability.CapabilityRegistry
import com.brain.gateway.ActionExecution
import com.brain.gateway.ActionExecutor
import com.brain.gateway.ActionGateway
import com.brain.gateway.InMemoryActionAuditLog
import com.brain.policy.PolicyBroker
import com.brain.policy.PolicyDecision
import com.brain.workflow.WorkflowDocument
import com.brain.workflow.WorkflowDocumentParser
import com.brain.workflow.WorkflowNode
import com.brain.workflow.WorkflowPreflight
import com.brain.workflow.WorkflowSource
import com.brain.workflow.WorkflowTrigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A porta usa o PolicyBroker e o ActionGateway REAIS; só o executor final é falso. */
class GatewayWorkflowRunPortTest {
    private class Env(grant: Boolean = true, allowExternalWhenScheduled: Boolean = false) {
        private val capability = CapabilityDefinition(
            id = "workflow.run", name = "workflow.run", description = "teste",
            category = CapabilityCategory.WORKFLOW, ownerId = "test", origin = "test",
            providedCapabilities = setOf("workflow.run"),
            availability = CapabilityAvailability.AVAILABLE,
            provenance = listOf(CapabilityProvenance("test", "test"))
        )
        private val registry = CapabilityRegistry(listOf(capability))
        private val granted = if (grant) listOf("workflow.run") else emptyList()
        val policy = PolicyBroker(
            allowedCapabilities = granted,
            actorCapabilities = mapOf("android-app" to granted)
        ).withCapabilityRegistry(registry)
        val audit = InMemoryActionAuditLog()
        val decisionsSeenByExecutor = mutableListOf<PolicyDecision>()
        private val executor = ActionExecutor { _, _, decision ->
            decisionsSeenByExecutor += decision
            ActionExecution(true, result = "resumo", evidence = listOf("test:executor"), provenance = listOf("test"))
        }
        val gateway = ActionGateway(registry, policy, executor, audit)
        val port = GatewayWorkflowRunPort(policy, gateway, "android-app", setOf("android:acct"), allowExternalWhenScheduled)
    }

    private val node = WorkflowNode("document", "workflow.run")

    private fun doc(extraFrontmatter: String = ""): WorkflowDocument = WorkflowDocumentParser.parse(
        buildString {
            appendLine("---")
            appendLine("name: demo")
            appendLine("version: 1.0.0")
            appendLine("description: teste")
            if (extraFrontmatter.isNotBlank()) appendLine(extraFrontmatter)
            appendLine("---")
            appendLine("Resuma o contexto fornecido.")
        },
        WorkflowSource.CUSTOM
    )

    @Test
    fun `preflight permite documento somente instrucao manual e agendado`() {
        val env = Env()
        assertEquals(WorkflowPreflight.Allowed, env.port.preflight(doc(), "run-1", WorkflowTrigger.MANUAL))
        assertEquals(WorkflowPreflight.Allowed, env.port.preflight(doc(), "run-2", WorkflowTrigger.SCHEDULED))
    }

    @Test
    fun `preflight bloqueia documento que declara effects sem chamar o executor`() {
        val env = Env()
        val result = env.port.preflight(doc("effects: [write_file]"), "run-1", WorkflowTrigger.MANUAL)
        assertTrue(result is WorkflowPreflight.Blocked)
        assertTrue(env.decisionsSeenByExecutor.isEmpty())
    }

    @Test
    fun `preflight bloqueia quando o ator nao tem o grant de workflow run`() {
        val env = Env(grant = false)
        val result = env.port.preflight(doc(), "run-1", WorkflowTrigger.SCHEDULED)
        assertTrue(result is WorkflowPreflight.Blocked)
        assertTrue((result as WorkflowPreflight.Blocked).reason.contains("DENY"))
    }

    @Test
    fun `executeBody passa pelo gateway real e deixa registro de auditoria com proveniencia`() {
        val env = Env()
        val step = env.port.executeBody(doc(), node, 1, "run-1", WorkflowTrigger.SCHEDULED)
        assertTrue(step.success)
        assertEquals("resumo", step.output["text"])
        assertEquals(1, env.decisionsSeenByExecutor.size)
        val record = env.audit.all().single()
        assertEquals("workflow.run", record.capability)
        assertTrue(record.provenance.contains("trigger:SCHEDULED"))
        assertTrue(record.provenance.contains("workflow:demo@1.0.0"))
    }

    @Test
    fun `executeBody sem grant e negado pelo gateway e o executor nunca roda`() {
        val env = Env(grant = false)
        val step = env.port.executeBody(doc(), node, 1, "run-1", WorkflowTrigger.MANUAL)
        assertFalse(step.success)
        assertNotNull(step.error)
        assertTrue(env.decisionsSeenByExecutor.isEmpty())
        assertEquals(1, env.audit.all().size)
        assertFalse(env.audit.all().single().success)
    }

    @Test
    fun `run agendado nao enxerga contas externas por padrao e o manual enxerga`() {
        val env = Env()
        env.port.executeBody(doc(), node, 1, "run-s", WorkflowTrigger.SCHEDULED)
        env.port.executeBody(doc(), node, 1, "run-m", WorkflowTrigger.MANUAL)
        assertTrue(env.decisionsSeenByExecutor[0].authorizedAccountIds.isEmpty())
        assertEquals(setOf("android:acct"), env.decisionsSeenByExecutor[1].authorizedAccountIds)
    }

    @Test
    fun `run agendado so enxerga contas externas com opt-in explicito`() {
        val env = Env(allowExternalWhenScheduled = true)
        env.port.executeBody(doc(), node, 1, "run-s", WorkflowTrigger.SCHEDULED)
        assertEquals(setOf("android:acct"), env.decisionsSeenByExecutor.single().authorizedAccountIds)
    }
}
