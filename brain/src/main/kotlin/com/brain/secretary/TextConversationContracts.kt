package com.brain.secretary

/** Decisão única do gate de saída humana. */
enum class SecretaryDecision { ACCEPT, BLOCK }

enum class BlockReason {
    LOCAL_KNOWLEDGE_MISS, FALLBACK_RESPONSE, EMPTY_RESPONSE, INTERNAL_ERROR,
    NON_USER_FACING_RESPONSE, INCOMPLETE_RESPONSE
}

enum class ConversationStatus { ANSWERED_LOCAL, LOCAL_KNOWLEDGE_MISS, RESEARCH_RESULT_RECEIVED, ANSWER_READY }

data class ConversationResult(
    val text: String,
    val status: ConversationStatus,
    val evidence: List<String> = emptyList(),
    val requestId: String = "legacy-request",
    val prompt: String = ""
)

data class SecretaryEvaluation(val decision: SecretaryDecision, val reason: BlockReason? = null) {
    init {
        if (decision == SecretaryDecision.ACCEPT) require(reason == null)
        if (decision == SecretaryDecision.BLOCK) require(reason != null)
    }
}

/** Gate determinístico: somente candidatos relacionados ao prompt chegam à UI. */
class DeterministicSecretaryGate {
    private val prohibitedFallbacks = listOf(
        "não reconheci uma resposta local confiável", "pode reformular", "não sei",
        "evidence found", "researchresult", "webresearch indisponível", "exception:"
    )

    fun evaluate(result: ConversationResult, recoveryAvailable: Boolean): SecretaryEvaluation {
        val text = result.text.trim()
        if (text.isBlank()) return SecretaryEvaluation(SecretaryDecision.BLOCK, BlockReason.EMPTY_RESPONSE)
        if (result.requestId.isBlank()) return SecretaryEvaluation(SecretaryDecision.BLOCK, BlockReason.NON_USER_FACING_RESPONSE)
        if (result.status == ConversationStatus.LOCAL_KNOWLEDGE_MISS && recoveryAvailable)
            return SecretaryEvaluation(SecretaryDecision.BLOCK, BlockReason.LOCAL_KNOWLEDGE_MISS)
        if (prohibitedFallbacks.any { text.contains(it, ignoreCase = true) })
            return SecretaryEvaluation(SecretaryDecision.BLOCK, BlockReason.FALLBACK_RESPONSE)
        if (result.status == ConversationStatus.RESEARCH_RESULT_RECEIVED && result.evidence.none { it == "chat:websearch:evidence" })
            return SecretaryEvaluation(SecretaryDecision.BLOCK, BlockReason.INCOMPLETE_RESPONSE)
        if (result.status == ConversationStatus.LOCAL_KNOWLEDGE_MISS)
            return SecretaryEvaluation(SecretaryDecision.BLOCK, BlockReason.LOCAL_KNOWLEDGE_MISS)
        if (result.status != ConversationStatus.ANSWER_READY && result.status != ConversationStatus.ANSWERED_LOCAL)
            return SecretaryEvaluation(SecretaryDecision.BLOCK, BlockReason.NON_USER_FACING_RESPONSE)
        val evidenceBackedException = result.evidence.any {
            it == "chat:conversation:local-miss" ||
                it == "chat:context:read-only" ||
                it == "chat:research-context-included"
        }
        if (result.prompt.isNotBlank() && !evidenceBackedException && !isSemanticallyRelated(result.prompt, text))
            return SecretaryEvaluation(SecretaryDecision.BLOCK, BlockReason.INCOMPLETE_RESPONSE)
        return SecretaryEvaluation(SecretaryDecision.ACCEPT)
    }

    /** Promove um candidato interno a UserResponse somente após a avaliação do gate. */
    fun accept(candidate: ConversationCandidate, recoveryAvailable: Boolean = false): UserResponse? {
        val evaluation = evaluate(
            ConversationResult(candidate.text, candidate.status, candidate.evidence, candidate.requestId, candidate.prompt),
            recoveryAvailable
        )
        return if (evaluation.decision == SecretaryDecision.ACCEPT) {
            UserResponse(candidate.text.trim(), candidate.evidence, candidate.requestId, candidate.requestId)
        } else null
    }

    private fun isSemanticallyRelated(prompt: String, response: String): Boolean {
        if (Regex("(?i)\\b(oi|olá|ola|bom dia|boa tarde|boa noite)\\b").containsMatchIn(prompt)) return true
        val stop = setOf("como", "funciona", "sobre", "qual", "quais", "explique", "fale", "o", "que", "é", "e", "a", "de", "do", "da", "um", "uma")
        val questionTerms = tokens(prompt).filter { it !in stop }
        if (questionTerms.isEmpty()) return true
        val answerTerms = tokens(response)
        return questionTerms.any { term -> answerTerms.any { answer -> answer.contains(term) || term.contains(answer) } }
    }

    private fun tokens(value: String): Set<String> = Regex("[\\p{L}\\p{N}]{3,}")
        .findAll(value.lowercase()).map { it.value }.toSet()
}

/** Resposta final: somente este tipo pode atravessar a fronteira Conversation → UI. */
data class UserResponse(
    val text: String,
    val evidence: List<String> = emptyList(),
    val requestId: String = "legacy-request",
    val conversationId: String? = null
)
