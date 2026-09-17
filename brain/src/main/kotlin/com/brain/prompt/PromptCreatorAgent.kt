package com.brain.prompt

/** Resultado da criação local — sempre disponível, nunca depende de rede. */
data class PromptCriado(
    val texto: String,
    val dominio: PromptDomain,
    val origem: String,
    val componentesDetectados: Set<String> = emptySet()
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
