package com.brain.policy

import com.brain.capability.CapabilityAvailability
import com.brain.capability.CapabilityCategory
import com.brain.capability.CapabilityDefinition
import com.brain.capability.CapabilityProvenance
import org.junit.Assert.assertEquals
import org.junit.Test

class PolicyBrokerCapabilityIntegrationTest {
    private val definition = CapabilityDefinition(
        id = "filesystem.read",
        name = "Filesystem Read",
        description = "leitura controlada",
        category = CapabilityCategory.TOOL,
        ownerId = "tool.owner",
        origin = "builtin",
        availability = CapabilityAvailability.AVAILABLE,
        provenance = listOf(CapabilityProvenance("test", "unit-test"))
    )

    private fun context(
        approval: ApprovalRequired = ApprovalRequired.NONE,
        sandbox: Boolean = true,
        risk: com.brain.execution.RiskClass = com.brain.execution.RiskClass.LOW,
        data: Set<String> = emptySet(),
        environment: String = "sandbox"
    ) = PolicyContext(
        runId = "run", taskId = "task", actor = "agent", riskClass = risk,
        approval = approval, sandboxRequired = sandbox, filesystemRoots = listOf("/safe"),
        dataClassifications = data, environment = environment
    )

    @Test
    fun `broker consulta capability registrada no registry universal`() {
        val registry = com.brain.capability.CapabilityRegistry(listOf(definition))
        val broker = PolicyBroker(actorCapabilities = mapOf("agent" to listOf("filesystem.read")))
            .withCapabilityRegistry(registry)

        val decision = broker.authorize("agent", "filesystem.read", "/safe/file", context())

        assertEquals(Decision.ALLOW, decision.decision)
        assertEquals(PolicyOutcome.ALLOW_WITH_LIMITS, decision.outcome)
    }

    @Test
    fun `approval e dados restritos produzem require approval sem executar`() {
        val broker = PolicyBroker(listOf("filesystem.read"), mapOf("agent" to listOf("filesystem.read")))
        val decision = broker.authorize(
            "agent", "filesystem.read", "/safe/file",
            context(risk = com.brain.execution.RiskClass.MEDIUM, data = setOf("restricted"))
        )

        assertEquals(Decision.ASK, decision.decision)
        assertEquals(PolicyOutcome.REQUIRE_APPROVAL, decision.outcome)
    }

    @Test
    fun `ambiente production impede capability de alto risco sem sandbox`() {
        val broker = PolicyBroker(listOf("filesystem.read"), mapOf("agent" to listOf("filesystem.read")))
        val decision = broker.authorize(
            "agent", "filesystem.read", "/safe/file",
            context(risk = com.brain.execution.RiskClass.HIGH, sandbox = false, environment = "production")
        )

        assertEquals(Decision.DENY, decision.decision)
        assertEquals(PolicyOutcome.DENY, decision.outcome)
    }
}
