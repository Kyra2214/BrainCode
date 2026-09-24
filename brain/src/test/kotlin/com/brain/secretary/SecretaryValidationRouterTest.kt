package com.brain.secretary

import com.brain.validation.FindingOwner
import com.brain.validation.ValidationResult
import com.brain.validation.ValidationStatus
import kotlin.test.Test
import kotlin.test.assertEquals

class SecretaryValidationRouterTest {
    @Test
    fun `falha de agente volta ao especialista`() {
        val result = ValidationResult(
            status = ValidationStatus.FAIL,
            contractId = "self-e2e:agent.code",
            taskId = "implementation",
            capability = "workspace.generate",
            agentId = "agent.code",
            failedChecks = listOf("result-or-evidence"),
            findingOwners = mapOf("result-or-evidence" to FindingOwner.AGENT)
        )
        val route = SecretaryValidationRouter.route(result)
        assertEquals(ValidationRouteAction.CORRECT_AGENT, route.action)
        assertEquals("agent.code", route.responsibleAgentId)
        assertEquals("implementation", route.taskId)
    }

    @Test
    fun `policy nao gera retry de agente`() {
        val result = ValidationResult(
            status = ValidationStatus.FAIL,
            contractId = "policy",
            taskId = "x",
            capability = "workspace.generate",
            agentId = "agent.code",
            findingOwners = mapOf("policy.denied" to FindingOwner.POLICY)
        )
        assertEquals(ValidationRouteAction.REPORT_POLICY, SecretaryValidationRouter.route(result).action)
    }

    @Test
    fun `needs input pergunta ao usuario`() {
        val result = ValidationResult(
            status = ValidationStatus.NEEDS_INPUT,
            contractId = "door.chat.light",
            taskId = "clarificar",
            capability = "chat.respond"
        )
        assertEquals(ValidationRouteAction.ASK_USER, SecretaryValidationRouter.route(result).action)
    }
}
