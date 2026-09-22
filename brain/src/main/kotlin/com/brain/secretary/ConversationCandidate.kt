package com.brain.secretary

/**
 * Candidato interno de resposta. Ele não é exibível: somente o Secretary pode
 * promovê-lo a UserResponse depois de validar origem, correlação e conteúdo.
 */
data class ConversationCandidate(
    val requestId: String,
    val prompt: String,
    val locale: String = "pt-BR",
    val status: ConversationStatus,
    val topic: String? = null,
    val source: String,
    val evidence: List<String> = emptyList(),
    val text: String
) {
    init {
        require(requestId.isNotBlank()) { "requestId é obrigatório" }
        require(prompt.isNotBlank()) { "prompt é obrigatório" }
        require(text.isNotBlank()) { "candidate text é obrigatório" }
    }
}
