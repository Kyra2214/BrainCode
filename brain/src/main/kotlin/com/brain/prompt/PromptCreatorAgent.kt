package com.brain.prompt

/** Resultado da criação local — sempre disponível, nunca depende de rede. */
data class PromptReasoningTrace(
    val intent: String,
    val mandatoryElements: List<String> = emptyList(),
    val evidence: List<String> = emptyList(),
    val assumptions: List<String> = emptyList(),
    val revisions: List<String> = emptyList()
) {
    fun merge(other: PromptReasoningTrace?): PromptReasoningTrace = other?.let {
        copy(
            intent = if (intent.isNotBlank()) intent else it.intent,
            mandatoryElements = (mandatoryElements + it.mandatoryElements).distinct(),
            evidence = (evidence + it.evidence).distinct(),
            assumptions = (assumptions + it.assumptions).distinct(),
            revisions = (revisions + it.revisions).distinct()
        )
    } ?: this
}

data class PromptCriado(
    val texto: String,
    val dominio: PromptDomain,
    val origem: String,
    val componentesDetectados: Set<String> = emptySet(),
    val reasoning: PromptReasoningTrace = PromptReasoningTrace(intent = "não especificada")
)

/**
 * Cria prompts do zero (ou reformula um prompt existente) usando regras e
 * composição semântica determinística — nunca lança exceção por falta de API,
 * porque a capacidade básica de criação não depende de rede nem de chave.
 */
interface PromptCreatorAgent {
    /**
     * @param pedido texto livre do usuário.
     * @param contexto template da Prompt Library já considerado compatível
     *   (referência/adaptação), quando existir.
     * @param contextoPesquisa conteúdo relevante já coletado por WebResearch
     *   (opcional) — enriquece a criação, nunca é obrigatório.
     */
    fun criar(
        pedido: String,
        contexto: PromptTemplate? = null,
        contextoPesquisa: String? = null
    ): PromptCriado

    /** Reformula/melhora um prompt já existente a partir de um pedido explícito ("melhore esse prompt"). */
    fun melhorarLocalmente(
        promptAtual: String,
        pedidoOriginal: String,
        pontosFracos: Set<String>,
        contextoPesquisa: String? = null
    ): PromptCriado
}
