package com.brain.router

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Implementação mínima do ApiCatalog (Fase C, evolui na Fase G).
 * Mantém os modelos carregados (ver ApiCatalogLoader) e um LiveStats
 * em memória por provider+modelo, atualizado a cada registrarResultado().
 *
 * A lógica de agregação (taxa de sucesso, latência média) é a mesma do
 * AiApiUsageTracker do IaBrain (brain/AiApiLearning.kt), só adaptada
 * para o formato LiveStats novo e para granularidade provider+modelo
 * em vez de só modelo.
 *
 * Ainda não persiste entre reinícios — isso é trabalho da Fase D
 * (memory): o ExperienceMemory vai alimentar esse mesmo LiveStats a
 * partir do histórico salvo, não substituí-lo.
 */
class InMemoryApiCatalog(
    private val modelos: List<ProviderModel>
) : ApiCatalog {

    private data class Acumulado(
        val sucesso: Int = 0,
        val falha: Int = 0,
        val latenciaTotalMs: Long = 0,
        val ultimoErro: ErroObservado? = null
    )

    private val stats = ConcurrentHashMap<String, Acumulado>()

    private fun chave(providerId: String, modeloId: String) = "$providerId::$modeloId"

    override fun listarModelos(): List<ProviderModel> = modelos

    override fun listarPorPapel(papel: PapelPipeline): List<ProviderModel> =
        modelos.filter { papel in it.papeisSugeridos }

    override fun statsAtuais(providerId: String, modeloId: String): LiveStats? {
        val acumulado = stats[chave(providerId, modeloId)] ?: return null
        val total = acumulado.sucesso + acumulado.falha
        return LiveStats(
            providerId = providerId,
            modeloId = modeloId,
            quotaRestanteEstimada = null, // TODO (Fase G): descontar por chamada quando existir janela real
            ultimoErro = acumulado.ultimoErro,
            latenciaMediaMs = if (total == 0) null else acumulado.latenciaTotalMs / total,
            taxaSucessoRecente = if (total == 0) null else acumulado.sucesso.toDouble() / total,
            atualizadoEm = Instant.now()
        )
    }

    override fun registrarResultado(
        providerId: String,
        modeloId: String,
        sucesso: Boolean,
        latenciaMs: Long,
        erro: ErroObservado?
    ) {
        stats.compute(chave(providerId, modeloId)) { _, atual ->
            val base = atual ?: Acumulado()
            base.copy(
                sucesso = base.sucesso + if (sucesso) 1 else 0,
                falha = base.falha + if (sucesso) 0 else 1,
                latenciaTotalMs = base.latenciaTotalMs + latenciaMs,
                // erro novo substitui o anterior; sucesso NÃO limpa o último erro
                // registrado (um acerto isolado não prova que a chave passou a ser válida).
                ultimoErro = erro ?: base.ultimoErro
            )
        }
    }
}
