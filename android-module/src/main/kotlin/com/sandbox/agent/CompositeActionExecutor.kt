package com.sandbox.agent

import com.brain.capability.CapabilityDefinition
import com.brain.gateway.ActionExecution
import com.brain.gateway.ActionExecutor
import com.brain.gateway.ActionRequest
import com.brain.policy.PolicyDecision

/**
 * Encaminha capabilities específicas para executores injetados pelo app e
 * mantém o executor Sandbox como fallback para todas as demais ações.
 */
class CompositeActionExecutor(
    private val overrides: Map<String, ActionExecutor>,
    private val defaultExecutor: ActionExecutor
) : ActionExecutor {
    override fun execute(
        request: ActionRequest,
        capability: CapabilityDefinition,
        decision: PolicyDecision
    ): ActionExecution {
        if (capability.category in setOf(
                com.brain.capability.CapabilityCategory.AGENT,
                com.brain.capability.CapabilityCategory.WORKFLOW
            ) && capability.id !in overrides) {
            return ActionExecution(
                false,
                error = "agent indisponível: executor dedicado não registrado para " + capability.id,
                provenance = listOf("composite:fail-closed", "agent:" + capability.id)
            )
        }
        return (overrides[capability.id] ?: defaultExecutor).execute(request, capability, decision)
    }
}
