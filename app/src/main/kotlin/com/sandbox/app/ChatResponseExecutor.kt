package com.sandbox.app

import com.brain.capability.CapabilityDefinition
import com.brain.gateway.ActionExecution
import com.brain.gateway.ActionExecutor
import com.brain.gateway.ActionRequest
import com.brain.policy.PolicyDecision
import com.brain.research.ResearchRequest
import com.brain.research.ResearchRunResult
import com.brain.research.WebResearchAgent
import com.brain.memory.KnowledgeLearningCycle
import com.brain.memory.ResearchKnowledgePromoter
import com.brain.secretary.ConversationResult
import com.brain.secretary.ConversationStatus
import com.brain.secretary.DeterministicSecretaryGate
import com.brain.secretary.SecretaryDecision
import com.brain.secretary.UserResponse
import com.brain.secretary.ConversationCandidate
import com.brain.text.InformationalQuestionClassifier
import com.brain.conversation.ConversationMetrics
import java.time.Clock

/**
 * Orquestra o ciclo textual. Conversation e WebResearch produzem dados internos;
 * somente UserResponse, liberado pelo Secretário, é devolvido como resultado da ação.
 */
class ChatResponseExecutor(
    clock: Clock = Clock.systemDefaultZone(),
    private val contextProvider: () -> ConversationContext = { ConversationContext() },
    private val conversationEngine: NoInferenceConversationEngine? = null,
    private val composer: ResponseComposer = ResponseComposer(clock, conversationEngine),
    private val researchFallback: WebResearchAgent? = null,
    private val knowledgeCycle: KnowledgeLearningCycle? = null,
    private val knowledgePromoter: ResearchKnowledgePromoter? = null,
    private val secretaryGate: DeterministicSecretaryGate = DeterministicSecretaryGate(),
    val metrics: ConversationMetrics = ConversationMetrics(),
    private val maxRecoveryAttempts: Int = 1
) : ActionExecutor {
    init { require(maxRecoveryAttempts in 0..1) { "o ciclo textual permite no máximo uma recuperação" } }

    override fun execute(request: ActionRequest, capability: CapabilityDefinition, decision: PolicyDecision): ActionExecution {
        val prompt = request.parameters["parameter.0"]?.trim().orEmpty()
        if (prompt.isBlank()) {
            return ActionExecution(false, error = "mensagem conversacional ausente", provenance = provenance(capability))
        }
        val suppliedResearch = request.parameters["parameter.1"]?.trim().orEmpty()
        val isClarification = request.parameters.values.any { it.startsWith("clarification.status=NEEDS_CLARIFICATION") }
        val context = contextProvider()
        val learned = knowledgeCycle?.recall(prompt)
        when {
            learned == null -> metrics.recordCacheMiss()
            learned.intent != null -> metrics.recordCacheHit("layer1")
            else -> metrics.recordCacheHit("layer2")
        }
        val localResponse = learned?.let {
            ConversationResponse(it.answer, "knowledge.learned", evidence = listOf("knowledge:validated", "knowledge:${it.provenance.name}"))
        } ?: conversationEngine?.respond(prompt, context)
        val localMiss = localResponse == null || localResponse.intent == "knowledge.unknown"
        val informational = isInformationalQuestion(prompt)
        val shouldRecover = suppliedResearch.isBlank() && !isClarification && localMiss && informational && researchFallback != null
        val evidence = mutableListOf("chat:conversation")
        var researchResult: ResearchRunResult? = null

        if (localMiss && shouldRecover && maxRecoveryAttempts == 1) {
            evidence += "chat:secretary:block"
            evidence += "chat:orchestrator:recovery"
            researchResult = requireNotNull(researchFallback).research(ResearchRequest(prompt, requestId = request.actionId, conversationId = request.parameters["conversationId"]))
            evidence += "chat:websearch:executed"
            if (researchResult.sources.isNotEmpty() && researchResult.evidence.isNotEmpty()) {
                evidence += "chat:websearch:evidence"
            }
        }

        val finalText: String
        val status: ConversationStatus
        when {
            researchResult?.answer?.isNotBlank() == true -> {
                val composed = composer.compose(prompt, research = researchResult.answer, context = context, localLookupCompleted = true)
                finalText = composed.text
                evidence += "chat:conversation:synthesis"
                status = ConversationStatus.ANSWER_READY
            }
            localMiss && shouldRecover -> {
                // A rota de recuperação foi consumida. A falha honesta também passa pelo gate.
                finalText = "Não encontrei fontes confiáveis suficientes para responder a essa pergunta agora."
                evidence += "chat:conversation:synthesis"
                status = ConversationStatus.ANSWER_READY
            }
            else -> {
                val composed = composer.compose(
                    prompt = prompt,
                    research = suppliedResearch,
                    clarification = isClarification,
                    context = context,
                    localLookupCompleted = conversationEngine != null,
                    precomputedConversation = localResponse
                )
                finalText = composed.text
                evidence += composed.evidence
                status = if (suppliedResearch.isNotBlank()) ConversationStatus.ANSWER_READY else ConversationStatus.ANSWERED_LOCAL
            }
        }

        val requestId = request.actionId
        evidence += "chat:request:$requestId"
        val conversation = ConversationResult(finalText, status, evidence, requestId = requestId, prompt = prompt)
        val evaluation = secretaryGate.evaluate(conversation, recoveryAvailable = shouldRecover && researchResult == null)
        if (evaluation.decision != SecretaryDecision.ACCEPT) {
            metrics.recordSecretary("content", accepted = false)
            return ActionExecution(
                false,
                error = "Secretário bloqueou a saída: ${evaluation.reason}",
                evidence = evidence + "chat:secretary:block",
                provenance = provenance(capability)
            )
        }

        metrics.recordSecretary("content", accepted = true)
        evidence += "chat:secretary:accept"
        if (researchResult?.answer?.isNotBlank() == true) {
            knowledgePromoter?.promote(ResearchRequest(prompt, requestId = request.actionId, conversationId = request.parameters["conversationId"]), researchResult)
        }
        val provenance = provenance(capability).toMutableList()
        if (researchResult != null) provenance += "research:auto-fallback-after-local-miss"
        if (researchResult != null) provenance += "source:dependency:network.research"
        val candidate = ConversationCandidate(requestId, prompt, status = status, source = if (researchResult != null) "web-research" else "local", evidence = evidence.distinct(), text = finalText)
        val promoted = secretaryGate.accept(candidate) ?: run {
            metrics.recordSecretary("form", accepted = false)
            return ActionExecution(
                false,
                error = "Secretário rejeitou o candidato na promoção final",
                evidence = evidence + "chat:secretary:block",
                provenance = provenance(capability)
            )
        }
        metrics.recordSecretary("form", accepted = true)
        return ActionExecution(
            true,
            evidence = evidence.distinct(),
            provenance = provenance.distinct(),
            researchSources = researchResult?.sources.orEmpty(),
            userResponse = promoted.copy(conversationId = request.parameters["conversationId"])
        )
    }

    private fun isInformationalQuestion(prompt: String): Boolean =
        InformationalQuestionClassifier.isRecoverable(prompt)

    private fun provenance(capability: CapabilityDefinition) = listOf("app:ChatResponseExecutor", "capability:${capability.id}")
}
