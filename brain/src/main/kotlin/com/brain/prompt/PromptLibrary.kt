package com.brain.prompt

import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap

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

/**
 * Liga a geração/reutilização de um prompt ao resultado real do ciclo que o consumiu.
 * A geração apenas registra uso pendente; o contador histórico só muda quando o ciclo
 * termina e o chamador informa sucesso/falha real.
 */
class PromptOutcomeTracker(private val library: PromptLibrary) {
    private data class Pending(val templateId: String)
    private val pending = ConcurrentHashMap<String, Pending>()

    fun markUsed(actionId: String, templateId: String) {
        if (actionId.isBlank() || templateId.isBlank()) return
        pending[actionId] = Pending(templateId)
    }

    fun recordOutcome(actionId: String, success: Boolean, cost: Double, elapsedMs: Long): Boolean {
        val usage = pending.remove(actionId) ?: return false
        runCatching {
            runBlocking { library.registrarResultado(usage.templateId, success, cost, elapsedMs) }
        }.onFailure {
            pending[actionId] = usage
        }
        return true
    }

    fun pendingCount(): Int = pending.size
}
