package com.brain.prompt

import kotlinx.coroutines.runBlocking
import java.util.Collections
import java.util.WeakHashMap
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

/** Liga a geração/reutilização de um prompt ao resultado real do ciclo que o consumiu. */
class PromptOutcomeTracker(private val library: PromptLibrary) {
    private data class Pending(val templateId: String)
    private val pending = ConcurrentHashMap<String, Pending>()

    fun markUsed(actionId: String, templateId: String) {
        if (actionId.isBlank() || templateId.isBlank()) return
        pending[actionId] = Pending(templateId)
    }

    fun recordOutcome(actionId: String, success: Boolean, cost: Double, elapsedMs: Long): Boolean {
        val usage = pending.remove(actionId) ?: return false
        val persisted = runCatching {
            runBlocking { library.registrarResultado(usage.templateId, success, cost, elapsedMs) }
        }.isSuccess
        if (!persisted) pending[actionId] = usage
        return persisted
    }

    fun pendingCount(): Int = pending.size
}

/** Um único tracker por instância da biblioteca dentro do processo Android. */
object PromptOutcomeTrackers {
    private val trackers = Collections.synchronizedMap(WeakHashMap<PromptLibrary, PromptOutcomeTracker>())

    fun forLibrary(library: PromptLibrary): PromptOutcomeTracker = synchronized(trackers) {
        trackers.getOrPut(library) { PromptOutcomeTracker(library) }
    }
}
