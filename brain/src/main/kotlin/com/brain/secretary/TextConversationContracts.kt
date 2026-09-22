package com.brain.secretary

/** Decisão única do gate de saída humana. */
enum class SecretaryDecision { ACCEPT, BLOCK }

enum class BlockReason {
    LOCAL_KNOWLEDGE_MISS,
    FALLBACK_RESPONSE,
    EMPTY_RESPONSE,
    INTERNAL_ERROR,
    NON_USER_FACING_RESPONSE,
    INCOMPLETE_RESPONSE
}

enum class ConversationStatus {
    ANSWERED_LOCAL,
    LOCAL_KNOWLEDGE_MISS,
    RESEARCH_RESULT_RECEIVED,
    ANSWER_READY
}

/** Resultado interno produzido por Conversation; nunca é uma resposta da UI. */
data class ConversationResult(
    val text: String,
    val status: ConversationStatus,
    val evidence: List<String> = emptyList()
)

data class SecretaryEvaluation(
    val decision: SecretaryDecision,
    val reason: BlockReason? = null
) {
    init {
        if (decision == SecretaryDecision.ACCEPT) require(reason == null) { "ACCEPT não pode ter motivo de bloqueio" }
        if (decision == SecretaryDecision.BLOCK) require(reason != null) { "BLOCK exige motivo" }
    }
}

/** Contrato centralizado e sem heurística espalhada entre agentes. */
class DeterministicSecretaryGate {
    private val prohibitedFallbacks = listOf(
        "não reconheci uma resposta local confiável",
        "pode reformular",
        "não sei",
        "evidence found",
        "researchresult",
        "webresearch indisponível",
        "exception:"
    )

    fun evaluate(result: ConversationResult, recoveryAvailable: Boolean): SecretaryEvaluation {
        val text = result.text.trim()
        if (text.isBlank()) return SecretaryEvaluation(SecretaryDecision.BLOCK, BlockReason.EMPTY_RESPONSE)
        if (result.status == ConversationStatus.LOCAL_KNOWLEDGE_MISS && recoveryAvailable) {
            return SecretaryEvaluation(SecretaryDecision.BLOCK, BlockReason.LOCAL_KNOWLEDGE_MISS)
        }
        if (prohibitedFallbacks.any { text.contains(it, ignoreCase = true) }) {
            return SecretaryEvaluation(SecretaryDecision.BLOCK, BlockReason.FALLBACK_RESPONSE)
        }
        if (result.status == ConversationStatus.RESEARCH_RESULT_RECEIVED && result.evidence.none { it == "chat:websearch:evidence" }) {
            return SecretaryEvaluation(SecretaryDecision.BLOCK, BlockReason.INCOMPLETE_RESPONSE)
        }
        if (result.status == ConversationStatus.LOCAL_KNOWLEDGE_MISS) {
            return SecretaryEvaluation(SecretaryDecision.BLOCK, BlockReason.LOCAL_KNOWLEDGE_MISS)
        }
        if (result.status != ConversationStatus.ANSWER_READY && result.status != ConversationStatus.ANSWERED_LOCAL) {
            return SecretaryEvaluation(SecretaryDecision.BLOCK, BlockReason.NON_USER_FACING_RESPONSE)
        }
        return SecretaryEvaluation(SecretaryDecision.ACCEPT)
    }
}

/** Resposta final: somente este tipo pode atravessar a fronteira Conversation → UI. */
data class UserResponse(val text: String, val evidence: List<String> = emptyList())
