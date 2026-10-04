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
import com.brain.conversation.ConversationInterpreterRegistry
import com.brain.conversation.ConversationMetrics
import com.brain.conversation.ConversationKnowledgeFlow
import com.brain.conversation.ConversationInterpreter
import com.brain.conversation.ConversationDrafter
import com.brain.conversation.OutputReviewer
import com.brain.provider.ToolCallingResult
import com.brain.policy.PolicyContext
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
    private val structuredInterpreter: ConversationInterpreter? = null,
    /** Camada 2 (API LLM) do fallback local→llm→web: só após miss local e com contas autorizadas pela Policy. */
    private val drafter: ConversationDrafter? = null,
    /** Loop opcional ligado ao ActionGateway único do controller; nulo mantém texto puro. */
    private val toolLoop: ((String, PolicyContext) -> ToolCallingResult?)? = null,
    private val outputReviewer: OutputReviewer? = null,
    private val secretaryGate: DeterministicSecretaryGate = DeterministicSecretaryGate(),
    val metrics: ConversationMetrics = ConversationMetrics(),
    private val maxRecoveryAttempts: Int = 1
) : ActionExecutor {
    init { require(maxRecoveryAttempts in 0..1) { "o ciclo textual permite no máximo uma recuperação" } }

    override fun execute(request: ActionRequest, capability: CapabilityDefinition, decision: PolicyDecision): ActionExecution {
        val prompt = request.parameters["parameter.0"]?.trim().orEmpty()
        if (prompt.isBlank()) return ActionExecution(false, error = "mensagem conversacional ausente", provenance = provenance(capability))
        val suppliedResearch = request.parameters["parameter.1"]?.trim().orEmpty()
        val isClarification = request.parameters.values.any { it.startsWith("clarification.status=NEEDS_CLARIFICATION") }
        val context = contextProvider()
        val evidence = mutableListOf("chat:conversation")
        val interpreter = structuredInterpreter ?: ConversationInterpreterRegistry.current
        val structuredRecall = if (knowledgeCycle != null) {
            ConversationKnowledgeFlow(knowledgeCycleMemory(knowledgeCycle), interpreter, metrics) { evidence += it }.recall(
                prompt,
                com.brain.conversation.ConversationContext(
                    requestId = request.actionId,
                    metadata = mapOf("idea" to (context.idea ?: ""), "requirements" to context.requirements.joinToString("|"))
                        .filterValues { it.isNotBlank() }
                )
            )
        } else null
        val learned = structuredRecall?.entry ?: knowledgeCycle?.recall(prompt)
        when {
            learned == null -> metrics.recordCacheMiss()
            learned.intent != null -> metrics.recordCacheHit("layer1")
            else -> metrics.recordCacheHit("layer2")
        }
        val localResponse = learned?.let {
            ConversationResponse(it.answer, "knowledge.learned", evidence = listOf("knowledge:validated", "knowledge:${it.provenance.name}"))
        } ?: conversationEngine?.respond(prompt, context)
        val localMiss = localResponse == null || localResponse.intent == "knowledge.unknown"
        val noWebRestriction = request.parameters.values.any { value -> value.contains("NO_WEB", ignoreCase = true) || value.contains("no web", ignoreCase = true) }
        val localCalculation = LocalArithmeticCalculator.calculate(prompt)

        // Camada 2 (API LLM): só entra quando a camada local não resolveu e recebe apenas
        // decision.authorizedAccountIds. O rascunho nunca vira resposta por conta própria:
        // passa pelo Secretário determinístico e, se configurado, também pelo outputReviewer.
        // Se falhar/reprovar, o ciclo segue normalmente para a camada 3 (web).
        var llmDraftText: String? = null
        if (localMiss && localCalculation == null && !composer.isLocalClockQuestion(prompt) && !isClarification && drafter != null) {
            val toolResult = toolLoop?.invoke(prompt, request.context)
            if (toolResult?.text?.isNotBlank() == true) {
                llmDraftText = toolResult.text
                evidence += "chat:llm:tools"
                evidence += toolResult.evidence
            }
            val draft = runCatching {
                if (llmDraftText == null) drafter?.rascunhar(
                        prompt,
                        com.brain.conversation.ConversationContext(requestId = request.actionId),
                        decision.authorizedAccountIds
                    ) else null
            }
                .getOrNull()?.takeIf { it.isNotBlank() }
            if (draft != null) {
                evidence += "chat:llm:implementer"
                val draftEvidence = evidence + "chat:llm:implementer"
                val gateCheck = secretaryGate.evaluate(
                    ConversationResult(draft, ConversationStatus.ANSWER_READY, draftEvidence, request.actionId, prompt, researchAttempted = false),
                    recoveryAvailable = false
                )
                val reviewOk = if (outputReviewer != null) {
                    val review = outputReviewer.conferir(prompt, draft)
                    metrics.recordLlmCall("implementer", "standard", review.respondeAoPedido && review.completo)
                    review.respondeAoPedido && review.completo
                } else true
                if (gateCheck.decision == SecretaryDecision.ACCEPT && reviewOk) {
                    llmDraftText = draft
                    evidence += "chat:llm:implementer:accepted"
                } else {
                    evidence += "chat:llm:implementer:rejected"
                }
            }
        }

        val shouldRecover = llmDraftText == null && suppliedResearch.isBlank() && localCalculation == null && !isClarification && !noWebRestriction && localMiss && researchFallback != null
        var researchResult: ResearchRunResult? = null

        if (localMiss && shouldRecover && maxRecoveryAttempts == 1) {
            evidence += "chat:secretary:block"
            evidence += "chat:orchestrator:recovery"
            researchResult = requireNotNull(researchFallback).research(ResearchRequest(prompt, requestId = request.actionId, conversationId = request.parameters["conversationId"]))
            evidence += "chat:websearch:executed"
            if (researchResult.sources.isNotEmpty() && researchResult.evidence.isNotEmpty()) evidence += "chat:websearch:evidence"
        }

        val finalText: String
        val status: ConversationStatus
        when {
            llmDraftText != null -> {
                finalText = llmDraftText
                evidence += "chat:conversation:synthesis"
                status = ConversationStatus.ANSWER_READY
            }
            researchResult?.answer?.isNotBlank() == true -> {
                finalText = trimToSentenceBoundary(researchResult.answer.trim(), maxChars = 1600)
                evidence += "chat:conversation:synthesis"
                status = ConversationStatus.ANSWER_READY
            }
            localMiss && shouldRecover -> {
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

        if (outputReviewer != null && llmDraftText == null) {
            val review = outputReviewer.conferir(prompt, finalText)
            metrics.recordLlmCall("reviewer", "standard", review.respondeAoPedido && review.completo)
            evidence += "chat:llm:reviewer"
            if (!review.respondeAoPedido || !review.completo) {
                metrics.recordSecretary("content", accepted = false)
                return ActionExecution(false, error = "QC da LLM rejeitou a resposta: ${review.observacoes.joinToString("; ")}", evidence = evidence + "chat:gate:content:rejected", provenance = provenance(capability))
            }
        }
        val requestId = request.actionId
        evidence += "chat:request:$requestId"
        val conversation = ConversationResult(finalText, status, evidence, requestId = requestId, prompt = prompt, researchAttempted = researchResult != null)
        val evaluation = secretaryGate.evaluate(conversation, recoveryAvailable = researchFallback != null && !noWebRestriction && !isClarification)
        if (evaluation.decision != SecretaryDecision.ACCEPT) {
            metrics.recordSecretary("content", accepted = false)
            return ActionExecution(false, error = "Secretário bloqueou a saída: ${evaluation.reason}", evidence = evidence + "chat:secretary:block", provenance = provenance(capability))
        }
        metrics.recordSecretary("content", accepted = true)
        evidence += "chat:secretary:accept"
        if (researchResult?.answer?.isNotBlank() == true) knowledgePromoter?.promote(ResearchRequest(prompt, requestId = request.actionId, conversationId = request.parameters["conversationId"]), researchResult)
        val provenance = provenance(capability).toMutableList()
        if (researchResult != null) provenance += "research:auto-fallback-after-local-miss"
        if (researchResult != null || suppliedResearch.isNotBlank()) provenance += "source:dependency:network.research"
        val candidate = ConversationCandidate(requestId, prompt, status = status, source = if (researchResult != null) "web-research" else "local", evidence = evidence.distinct(), text = finalText, researchAttempted = researchResult != null)
        val promoted = secretaryGate.accept(candidate) ?: run {
            metrics.recordSecretary("form", accepted = false)
            return ActionExecution(false, error = "Secretário rejeitou o candidato na promoção final", evidence = evidence + "chat:secretary:block", provenance = provenance)
        }
        metrics.recordSecretary("form", accepted = true)
        return ActionExecution(true, result = promoted.text, evidence = evidence.distinct(), provenance = provenance.distinct(), researchSources = researchResult?.sources.orEmpty(), userResponse = promoted.copy(conversationId = request.parameters["conversationId"]))
    }

    private fun knowledgeCycleMemory(cycle: KnowledgeLearningCycle): com.brain.memory.KnowledgeMemory = cycle.memoryForIntegration()
    private fun trimToSentenceBoundary(text: String, maxChars: Int): String {
        if (text.length <= maxChars) return text
        val cortado = text.take(maxChars)
        val ultimoPonto = listOf('.', '!', '?').mapNotNull { c -> cortado.lastIndexOf(c).takeIf { it > 0 } }.maxOrNull()
        return if (ultimoPonto != null && ultimoPonto > maxChars / 2) cortado.substring(0, ultimoPonto + 1) else "$cortado…"
    }
    private fun provenance(capability: CapabilityDefinition) = listOf("app:ChatResponseExecutor", "capability:${capability.id}")
}
