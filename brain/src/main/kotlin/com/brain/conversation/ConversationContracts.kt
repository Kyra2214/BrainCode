package com.brain.conversation

import com.brain.secretary.OrderIntent

/**
 * Contexto mínimo e imutável disponibilizado ao braço conversacional.
 * O contexto é fornecido pelo Secretário/orquestrador; a LLM não o persiste.
 */
data class ConversationContext(
    val requestId: String = "conversation-request",
    val conversationId: String? = null,
    val priorTurns: List<String> = emptyList(),
    val metadata: Map<String, String> = emptyMap()
)

/** Sugestão não vinculante que o Secretário pode aceitar ou ignorar. */
data class OrderIntentSugerido(
    val door: com.brain.secretary.Door,
    val intent: String? = null,
    val confidence: Double? = null,
    val rationale: String? = null
) {
    init {
        require(confidence == null || confidence in 0.0..1.0) {
            "confidence precisa estar entre 0 e 1"
        }
    }
}

/** Chamado somente quando o Secretário detecta ambiguidade. */
fun interface IntentAdvisor {
    /**
     * Recebe o prompt como dado e a classificação determinística tentativa.
     * O retorno é apenas uma sugestão; a aplicação continua sob controle do Secretário.
     */
    fun revisarClassificacao(prompt: String, classificacaoTentativa: OrderIntent): OrderIntentSugerido
}

/** Extrai estrutura para o agente e para o registro de memória futura. */
fun interface ConversationInterpreter {
    fun extrairEstrutura(prompt: String, context: ConversationContext): EstruturaExtraida
}

data class EstruturaExtraida(
    val intent: String,
    val normalizedQuery: String,
    val entities: Map<String, String> = emptyMap(),
    val parametrosParaAgente: Map<String, String> = emptyMap()
) {
    init {
        require(intent.isNotBlank()) { "intent não pode ser vazio" }
        require(normalizedQuery.isNotBlank()) { "normalizedQuery não pode ser vazio" }
    }
}

/** QC da saída bruta do agente; não toma decisão de aprovação. */
fun interface OutputReviewer {
    fun conferir(promptOriginal: String, saidaDoAgente: String): RevisaoAchados
}

data class RevisaoAchados(
    val respondeAoPedido: Boolean,
    val completo: Boolean,
    val observacoes: List<String> = emptyList()
)

/** Implementação neutra para wiring e testes offline, sem inferência nem rede. */
object NoOpIntentAdvisor : IntentAdvisor {
    override fun revisarClassificacao(
        prompt: String,
        classificacaoTentativa: OrderIntent
    ): OrderIntentSugerido = OrderIntentSugerido(
        door = classificacaoTentativa.door,
        rationale = "sem revisão conversacional configurada"
    )
}

/** Implementação neutra que preserva o texto como consulta normalizada. */
object NoOpConversationInterpreter : ConversationInterpreter {
    override fun extrairEstrutura(prompt: String, context: ConversationContext): EstruturaExtraida {
        val normalized = prompt.trim().lowercase().replace(Regex("\\s+"), " ")
        return EstruturaExtraida(intent = "UNCLASSIFIED", normalizedQuery = normalized)
    }
}

/** Implementação neutra que não inventa achados de qualidade. */
object NoOpOutputReviewer : OutputReviewer {
    override fun conferir(promptOriginal: String, saidaDoAgente: String): RevisaoAchados =
        RevisaoAchados(
            respondeAoPedido = saidaDoAgente.isNotBlank(),
            completo = saidaDoAgente.isNotBlank()
        )
}
