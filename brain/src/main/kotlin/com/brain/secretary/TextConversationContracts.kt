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
    val prompt: String = "",
    /** True somente depois de uma execução real do WebResearchAgent. */
    val researchAttempted: Boolean = false
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
        "evidence found", "researchresult", "webresearch indisponível", "exception:",
        "plano concluído", "pass", "readiness", "critique", "evidence", "trace", "provider", "pipeline",
        "verification", "verificação", "secretário bloqueou", "secretário rejeitou"
    )

    fun evaluate(result: ConversationResult, recoveryAvailable: Boolean): SecretaryEvaluation {
        val text = result.text.trim()
        if (text.isBlank()) return SecretaryEvaluation(SecretaryDecision.BLOCK, BlockReason.EMPTY_RESPONSE)
        if (result.requestId.isBlank()) return SecretaryEvaluation(SecretaryDecision.BLOCK, BlockReason.NON_USER_FACING_RESPONSE)
        if (recoveryAvailable && !result.researchAttempted && result.evidence.any { it == "chat:conversation:local-miss" })
            return SecretaryEvaluation(SecretaryDecision.BLOCK, BlockReason.LOCAL_KNOWLEDGE_MISS)
        if (result.status == ConversationStatus.LOCAL_KNOWLEDGE_MISS && recoveryAvailable && !result.researchAttempted)
            return SecretaryEvaluation(SecretaryDecision.BLOCK, BlockReason.LOCAL_KNOWLEDGE_MISS)
        if (prohibitedFallbacks.any { text.contains(it, ignoreCase = true) })
            return SecretaryEvaluation(SecretaryDecision.BLOCK, BlockReason.FALLBACK_RESPONSE)
        if (result.status == ConversationStatus.RESEARCH_RESULT_RECEIVED && result.evidence.none { it == "chat:websearch:evidence" })
            return SecretaryEvaluation(SecretaryDecision.BLOCK, BlockReason.INCOMPLETE_RESPONSE)
        if (result.status == ConversationStatus.LOCAL_KNOWLEDGE_MISS)
            return SecretaryEvaluation(SecretaryDecision.BLOCK, BlockReason.LOCAL_KNOWLEDGE_MISS)
        if (result.status != ConversationStatus.ANSWER_READY && result.status != ConversationStatus.ANSWERED_LOCAL)
            return SecretaryEvaluation(SecretaryDecision.BLOCK, BlockReason.NON_USER_FACING_RESPONSE)
        val researchExhaustedException = result.researchAttempted &&
            text.contains("não encontrei fontes confiáveis", ignoreCase = true)
        val evidenceBackedException = researchExhaustedException || result.evidence.any {
            it == "chat:conversation:local-miss" ||
                it == "chat:context:read-only" ||
                it == "chat:research-context-included"
        } && (!recoveryAvailable || result.researchAttempted)
        val webResearchBacked = result.researchAttempted && result.evidence.any { it == "chat:websearch:evidence" }
        val plausibleResearchAnswer = webResearchBacked && isPlausibleResearchAnswer(text)
        if (result.prompt.isNotBlank() && !evidenceBackedException && !isSemanticallyRelated(result.prompt, text) && !plausibleResearchAnswer)
            return SecretaryEvaluation(SecretaryDecision.BLOCK, BlockReason.INCOMPLETE_RESPONSE)
        return SecretaryEvaluation(SecretaryDecision.ACCEPT)
    }

    /** Promove um candidato interno a UserResponse somente após a avaliação do gate. */
    fun accept(candidate: ConversationCandidate, recoveryAvailable: Boolean = false): UserResponse? {
        val evaluation = evaluate(
            ConversationResult(candidate.text, candidate.status, candidate.evidence, candidate.requestId, candidate.prompt, candidate.researchAttempted),
            recoveryAvailable
        )
        return if (evaluation.decision == SecretaryDecision.ACCEPT) {
            UserResponse(candidate.text.trim(), candidate.evidence, candidate.requestId, candidate.requestId)
        } else null
    }

    private fun isPlausibleResearchAnswer(response: String): Boolean {
        val normalized = response.trim().lowercase()
        if (normalized.length < 3) return false
        if (normalized.matches(Regex("(?i)^(ok|certo|sim|não|nao|entendi|beleza|claro)[.!?]*$"))) return false
        if (prohibitedFallbacks.any { normalized.contains(it) }) return false
        return Regex("[\\p{L}\\p{N}]{3,}").containsMatchIn(normalized)
    }

    private fun isSemanticallyRelated(prompt: String, response: String): Boolean {
        if (Regex("(?i)\\b(oi|olá|ola|bom dia|boa tarde|boa noite)\\b").containsMatchIn(prompt)) return true

        // Respostas aritméticas determinísticas não precisam compartilhar tokens com a
        // expressão de entrada: "35 × 2" -> "70" é semanticamente correto mesmo sem
        // qualquer sobreposição lexical. O cálculo já foi executado pelo caminho local;
        // este gate só precisa reconhecer o contrato de saída numérica.
        val arithmeticPrompt = Regex(
            "(?i)^\\s*(?:(?:calcule|calcular|quanto\\s+(?:é|e)|qual\\s+o\\s+resultado\\s+de)\\s+)?[0-9\\s.,()+\\-*/×÷xX%]+(?:\\s+de\\s+[0-9\\s.,()+\\-*/×÷xX%]+)?[?!.]*\\s*$"
        ).matches(prompt)
        val numericResponse = Regex("^\\s*-?[0-9]+(?:[.,][0-9]+)?%?\\s*$").matches(response)
        if (arithmeticPrompt && numericResponse) return true

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
