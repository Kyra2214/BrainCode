package com.sandbox.app

import com.brain.capability.CapabilityDefinition
import com.brain.gateway.ActionExecution
import com.brain.gateway.ActionExecutor
import com.brain.gateway.ActionRequest
import com.brain.policy.PolicyDecision
import com.brain.research.ResearchRequest
import com.brain.research.WebResearchAgent
import com.brain.memory.KnowledgeLearningCycle
import com.brain.memory.ResearchKnowledgePromoter
import java.time.Clock

/**
 * Adaptador da capability conversacional. Não decide conteúdo por heurística própria:
 * entrega dados autorizados ao ResponseComposer e anexa proveniência operacional.
 */
class ChatResponseExecutor(
    clock: Clock = Clock.systemDefaultZone(),
    private val contextProvider: () -> ConversationContext = { ConversationContext() },
    private val conversationEngine: NoInferenceConversationEngine? = null,
    private val composer: ResponseComposer = ResponseComposer(clock, conversationEngine),
    private val researchFallback: WebResearchAgent? = null,
    private val knowledgeCycle: KnowledgeLearningCycle? = null,
    private val knowledgePromoter: ResearchKnowledgePromoter? = null
) : ActionExecutor {
    override fun execute(request: ActionRequest, capability: CapabilityDefinition, decision: PolicyDecision): ActionExecution {
        val prompt = request.parameters["parameter.0"]?.trim().orEmpty()
        if (prompt.isBlank()) {
            return ActionExecution(false, error = "mensagem conversacional ausente", provenance = provenance(capability))
        }
        val research = request.parameters["parameter.1"]?.trim().orEmpty()
        val isClarification = request.parameters.values.any { it.startsWith("clarification.status=NEEDS_CLARIFICATION") }
        val context = contextProvider()
        val learned = knowledgeCycle?.recall(prompt)
        val localResponse = learned?.let {
            ConversationResponse(it.answer, "knowledge.learned", evidence = listOf("knowledge:validated", "knowledge:${it.provenance.name}"))
        } ?: conversationEngine?.respond(prompt, context)
        val localMiss = localResponse == null || localResponse.intent == "knowledge.unknown"
        val automaticFallbackEligible = research.isBlank() && !isClarification && localMiss && isInformationalQuestion(prompt)
        val automaticResearchResult = if (automaticFallbackEligible) {
            researchFallback?.research(ResearchRequest(prompt))
        } else null
        val automaticResearch = automaticResearchResult?.answer.orEmpty()
        if (automaticResearchResult != null && automaticResearch.isNotBlank()) {
            knowledgePromoter?.promote(ResearchRequest(prompt), automaticResearchResult)
        }
        val finalResearch = research.ifBlank { automaticResearch }
        if (automaticFallbackEligible && researchFallback != null && automaticResearch.isBlank()) {
            return ActionExecution(
                true,
                result = "Não consegui encontrar fontes confiáveis para responder isso agora.",
                evidence = listOf("chat:local-miss", "web-research:fallback-failed"),
                provenance = provenance(capability) + "research:auto-fallback-after-local-miss"
            )
        }
        val composed = composer.compose(
            prompt = prompt,
            research = finalResearch,
            clarification = isClarification,
            context = context,
            localLookupCompleted = conversationEngine != null,
            precomputedConversation = localResponse
        )
        val provenance = provenance(capability).toMutableList()
        if (finalResearch.isNotBlank()) provenance += "source:dependency:network.research"
        if (automaticResearch.isNotBlank()) provenance += "research:auto-fallback-after-local-miss"
        return ActionExecution(true, result = composed.text, evidence = composed.evidence, provenance = provenance)
    }

    private fun isInformationalQuestion(prompt: String): Boolean {
        val normalized = prompt.lowercase()
        return prompt.contains("?") || normalized.startsWith("o que ") || normalized.startsWith("qual ") ||
            normalized.startsWith("quais ") || normalized.startsWith("como ") || normalized.startsWith("quem ") ||
            normalized.contains("explique") || normalized.contains("como funciona")
    }

    private fun provenance(capability: CapabilityDefinition) = listOf("app:ChatResponseExecutor", "capability:${capability.id}")
}
