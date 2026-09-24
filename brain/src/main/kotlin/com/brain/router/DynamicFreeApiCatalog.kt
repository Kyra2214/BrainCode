package com.brain.router

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Runtime catalog backed by live provider discovery. The seed list is only a
 * bootstrap; successful refreshes replace the models for that provider.
 */
class DynamicFreeApiCatalog(
    seed: List<ProviderModel>,
    private val providers: List<DynamicApiProvider>,
    private val discovery: DynamicFreeApiModelDiscovery,
    private val refreshEveryMs: Long = 0L
) : ApiCatalog {
    private val models = ConcurrentHashMap<String, List<ProviderModel>>()
    private val stats = ConcurrentHashMap<String, Stats>()
    private val refreshedAt = ConcurrentHashMap<String, Long>()

    init {
        seed.groupBy { it.providerId }.forEach { (provider, values) -> models[provider] = values }
    }

    override fun listarModelos(): List<ProviderModel> {
        refreshIfNeeded()
        return models.values.flatten()
    }

    override fun listarPorPapel(papel: PapelPipeline): List<ProviderModel> {
        refreshIfNeeded()
        return models.values.flatten().filter { papel in it.papeisSugeridos }
    }

    override fun statsAtuais(providerId: String, modeloId: String): LiveStats? {
        val s = stats[key(providerId, modeloId)] ?: return null
        val total = s.success + s.failure
        return LiveStats(providerId, modeloId, null, s.lastError, if (total == 0) null else s.latencyMs / total, if (total == 0) null else s.success.toDouble() / total, Instant.now())
    }

    override fun registrarResultado(providerId: String, modeloId: String, sucesso: Boolean, latenciaMs: Long, erro: ErroObservado?) {
        stats.compute(key(providerId, modeloId)) { _, old ->
            val s = old ?: Stats()
            s.copy(
                success = s.success + if (sucesso) 1 else 0,
                failure = s.failure + if (sucesso) 0 else 1,
                latencyMs = s.latencyMs + latenciaMs,
                lastError = erro ?: s.lastError
            )
        }
    }

    /** Atualiza uma API específica antes de usá-la; não depende de lista fixa de modelos. */
    fun refreshProvider(providerId: String): List<ProviderModel> {
        val provider = providers.firstOrNull { it.providerId == providerId } ?: return models[providerId].orEmpty()
        val discovered = discovery.refresh(provider)
        if (discovered.isNotEmpty()) {
            models[providerId] = discovered
            refreshedAt[providerId] = System.currentTimeMillis()
        }
        return models[providerId].orEmpty()
    }

    /**
     * Verifica se pelo menos um provider do catálogo consegue descobrir modelos
     * usando as credenciais atualmente fornecidas ao discovery. O seed sozinho
     * não conta como credencial válida.
     */
    fun hasUsableApiKey(): Boolean = providers.any { provider ->
        discovery.refresh(provider).isNotEmpty()
    }

    fun refreshAll(): List<ProviderModel> {
        providers.forEach { refreshProvider(it.providerId) }
        return models.values.flatten()
    }

    private fun refreshIfNeeded() {
        val now = System.currentTimeMillis()
        providers.forEach { provider ->
            val last = refreshedAt[provider.providerId] ?: Long.MIN_VALUE
            if (refreshEveryMs == 0L || now - last >= refreshEveryMs) refreshProvider(provider.providerId)
        }
    }

    private fun key(provider: String, model: String) = "$provider::$model"
    private data class Stats(val success: Int = 0, val failure: Int = 0, val latencyMs: Long = 0L, val lastError: ErroObservado? = null)
}
