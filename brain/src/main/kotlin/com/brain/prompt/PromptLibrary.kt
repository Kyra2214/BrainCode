package com.brain.prompt

/** Biblioteca operacional de prompts reutilizáveis. */
data class PromptTemplate(
    val id: String,
    val versao: Int,
    val finalidade: String,
    val contextoDeUso: String,
    val skillRelacionada: String?,
    val agenteRelacionado: String?,
    val textoTemplate: String,
    val taxaSucesso: Double,
    val custoMedio: Double,
    val tempoMedioMs: Long,
    val historicoMelhorias: List<String> = emptyList(),
    /** Execuções reais usadas para calcular taxaSucesso. Seeds começam em 0. */
    val amostrasObservadas: Int = 0
)

interface PromptLibrary {
    suspend fun buscarPorContexto(contextoDeUso: String): List<PromptTemplate>
    suspend fun salvarNovaVersao(template: PromptTemplate)

    /** Chamado depois de cada uso real do template. */
    suspend fun registrarResultado(templateId: String, sucesso: Boolean, custo: Double, tempoMs: Long)
}

interface PromptLibrarySnapshot {
    fun buscarPorContextoSnapshot(contextoDeUso: String): List<PromptTemplate>
}
