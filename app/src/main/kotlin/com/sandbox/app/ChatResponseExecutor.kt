package com.sandbox.app

import com.brain.capability.CapabilityDefinition
import com.brain.gateway.ActionExecution
import com.brain.gateway.ActionExecutor
import com.brain.gateway.ActionRequest
import com.brain.policy.PolicyDecision
import java.time.Clock

/**
 * Adaptador da capability conversacional. Não decide conteúdo por heurística própria:
 * entrega dados autorizados ao ResponseComposer e anexa proveniência operacional.
 */
class ChatResponseExecutor(
    clock: Clock = Clock.systemDefaultZone(),
    private val contextProvider: () -> ConversationContext = { ConversationContext() },
    private val composer: ResponseComposer = ResponseComposer(clock)
) : ActionExecutor {
    override fun execute(request: ActionRequest, capability: CapabilityDefinition, decision: PolicyDecision): ActionExecution {
        val prompt = request.parameters["parameter.0"]?.trim().orEmpty()
        if (prompt.isBlank()) {
            return ActionExecution(false, error = "mensagem conversacional ausente", provenance = provenance(capability))
        }
        val research = request.parameters["parameter.1"]?.trim().orEmpty()
        val isClarification = request.parameters.values.any { it.startsWith("clarification.status=NEEDS_CLARIFICATION") }
        val composed = composer.compose(prompt, research, isClarification, contextProvider())
        val provenance = provenance(capability).toMutableList()
        if (research.isNotBlank()) provenance += "source:dependency:network.research"
        return ActionExecution(true, result = composed.text, evidence = composed.evidence, provenance = provenance)
    }

    private fun provenance(capability: CapabilityDefinition) = listOf("app:ChatResponseExecutor", "capability:${capability.id}")
}
