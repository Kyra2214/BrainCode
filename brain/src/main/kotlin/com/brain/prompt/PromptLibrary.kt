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
    val amostrasObservadas: Int = 0,
    /** Aposentado = fora de busca/reuso, mas preservado no armazenamento para auditoria/histórico.
     *  Pode ser automático (taxaSucesso consistentemente baixa) ou manual ([PromptLibrary.aposentar]). */
    val aposentado: Boolean = false
)

interface PromptLibrary {
    suspend fun buscarPorContexto(contextoDeUso: String): List<PromptTemplate>

    /** Persiste uma nova versão do template. A biblioteca é a única responsável por decidir se o
     *  conteúdo é uma duplicata de algo já existente (merge de versão/estatísticas) ou um template
     *  novo — o caller nunca deve tentar adivinhar isso por conta própria.
     *  @return o id realmente persistido (pode diferir de [template].id quando a biblioteca
     *  detectar que o conteúdo é duplicata de um template já existente e mesclar nele). */
    suspend fun salvarNovaVersao(template: PromptTemplate): String

    /** Chamado depois de cada uso real do template. */
    suspend fun registrarResultado(templateId: String, sucesso: Boolean, custo: Double, tempoMs: Long)

    /** Aposenta um template manualmente: continua existindo (histórico/auditoria) mas some da
     *  busca/reuso. @return false se o id não existe. */
    suspend fun aposentar(templateId: String): Boolean
}

interface PromptLibrarySnapshot {
    fun buscarPorContextoSnapshot(contextoDeUso: String): List<PromptTemplate>
}

/**
 * Liga a geração/reutilização de um prompt ao resultado real do ciclo que o consumiu.
 *
 * Dois sinais distintos alimentam `taxaSucesso`, nunca confundidos:
 * - técnico ([recordTechnicalOutcome]): a geração completou sem erro/validação — automático,
 *   registrado logo após a execução do passo;
 *   entregue não significa que o prompt era bom, só que não quebrou;
 * - real do usuário ([recordUserFeedback]): reação explícita (👍/👎) de quem recebeu o prompt —
 *   só existe quando o passo técnico foi bem-sucedido (não há o que avaliar se nem foi entregue).
 */
class PromptOutcomeTracker(private val library: PromptLibrary) {
    private data class Usage(val templateId: String)
    private val pending = ConcurrentHashMap<String, Usage>()
    private val delivered = ConcurrentHashMap<String, Usage>()

    fun markUsed(actionId: String, templateId: String) {
        if (actionId.isBlank() || templateId.isBlank()) return
        pending[actionId] = Usage(templateId)
    }

    /** Chamado automaticamente logo após a execução do passo (sucesso/falha técnica). */
    fun recordTechnicalOutcome(actionId: String, success: Boolean, cost: Double, elapsedMs: Long): Boolean {
        val usage = pending.remove(actionId) ?: return false
        val persisted = runCatching {
            runBlocking { library.registrarResultado(usage.templateId, success, cost, elapsedMs) }
        }.isSuccess
        if (!persisted) { pending[actionId] = usage; return false }
        if (success) delivered[actionId] = usage
        return true
    }

    /** Chamado quando o usuário reage explicitamente ao prompt entregue. Sinal mais forte que o técnico. */
    fun recordUserFeedback(actionId: String, positivo: Boolean): Boolean {
        val usage = delivered.remove(actionId) ?: return false
        return runCatching {
            runBlocking { library.registrarResultado(usage.templateId, positivo, 0.0, 0L) }
        }.isSuccess
    }

    fun pendingCount(): Int = pending.size
    fun awaitingFeedbackCount(): Int = delivered.size
}

/** Um único tracker por instância da biblioteca dentro do processo Android. */
object PromptOutcomeTrackers {
    private val trackers = Collections.synchronizedMap(WeakHashMap<PromptLibrary, PromptOutcomeTracker>())

    fun forLibrary(library: PromptLibrary): PromptOutcomeTracker = synchronized(trackers) {
        trackers.getOrPut(library) { PromptOutcomeTracker(library) }
    }
}
