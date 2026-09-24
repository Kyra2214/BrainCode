package com.brain.router

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/** Descrição de uma API que deve ser consultada para descobrir os modelos atuais. */
data class DynamicApiProvider(
    val providerId: String,
    val modelsEndpoint: String,
    val papers: List<PapelPipeline>,
    val defaultContextTokens: Int? = null,
    /** Quando a API não publica preço por modelo, o acesso FREE_TIER do provider é a fonte de verdade. */
    val providerFreeTier: Boolean = true
) {
    init {
        require(providerId.isNotBlank())
        require(URI(modelsEndpoint).scheme.equals("https", ignoreCase = true))
    }
}

data class DiscoveredApiModel(
    val id: String,
    val active: Boolean = true,
    val chatCapable: Boolean = true,
    val contextTokens: Int? = null,
    val pricingKnown: Boolean = false,
    val inputPrice: String? = null,
    val outputPrice: String? = null
)

/** Transporte separado da política: facilita testes e permite trocar HTTP por outro transporte. */
fun interface ApiModelDiscoveryTransport {
    fun list(provider: DynamicApiProvider, apiKey: String): List<DiscoveredApiModel>
}

/** Descobre endpoints OpenAI-compatible sem manter uma lista de modelos no código. */
class OpenAiCompatibleModelDiscoveryTransport(
    private val connectTimeoutMs: Int = 8_000,
    private val readTimeoutMs: Int = 12_000
) : ApiModelDiscoveryTransport {
    override fun list(provider: DynamicApiProvider, apiKey: String): List<DiscoveredApiModel> {
        val connection = (URL(provider.modelsEndpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Accept", "application/json")
        }
        return try {
            if (connection.responseCode !in 200..299) return emptyList()
            val root = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val models = root.optJSONArray("data") ?: root.optJSONArray("models") ?: JSONArray()
            buildList {
                for (index in 0 until models.length()) {
                    val item = models.optJSONObject(index) ?: continue
                    val id = item.optString("id").trim()
                    if (id.isBlank()) continue
                    val pricing = item.optJSONObject("pricing")
                    add(
                        DiscoveredApiModel(
                            id = id,
                            active = item.optBoolean("active", !item.optBoolean("archived", false)),
                            chatCapable = chatCapable(item),
                            contextTokens = item.optIntOrNull("context_window") ?: item.optIntOrNull("max_context_length") ?: item.optIntOrNull("contextLength"),
                            pricingKnown = pricing != null,
                            inputPrice = pricing?.optString("prompt", null),
                            outputPrice = pricing?.optString("completion", null)
                        )
                    )
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun chatCapable(item: JSONObject): Boolean {
        val capabilities = item.optJSONObject("capabilities")
        if (capabilities != null && capabilities.has("completion_chat")) return capabilities.optBoolean("completion_chat")
        val actions = item.optJSONArray("supported_actions") ?: return true
        for (i in 0 until actions.length()) if (actions.optString(i) in setOf("generateContent", "chat.completions", "chat")) return true
        return false
    }
}

/**
 * Faz o refresh sob demanda e mantém somente modelos elegíveis ao acesso gratuito.
 * Não existe allowlist de IDs: o provider informa o que existe hoje.
 */
class DynamicFreeApiModelDiscovery(
    private val transport: ApiModelDiscoveryTransport = OpenAiCompatibleModelDiscoveryTransport(),
    private val apiKey: (String) -> String?,
    private val cache: MutableMap<String, List<ProviderModel>> = ConcurrentHashMap()
) {
    fun refresh(provider: DynamicApiProvider): List<ProviderModel> {
        val key = apiKey(provider.providerId)?.takeIf { it.isNotBlank() } ?: return cache[provider.providerId].orEmpty()
        val discovered = runCatching { transport.list(provider, key) }.getOrElse { return cache[provider.providerId].orEmpty() }
        val models = discovered
            .asSequence()
            .filter { it.active && it.chatCapable }
            .filter { isFree(provider, it) }
            .map {
                ProviderModel(
                    providerId = provider.providerId,
                    modeloId = it.id,
                    papeisSugeridos = provider.papers,
                    janela = JanelaLimite(),
                    contextoMaximoTokens = it.contextTokens ?: provider.defaultContextTokens
                )
            }
            .distinctBy { it.modeloId }
            .toList()
        cache[provider.providerId] = models
        return models
    }

    fun refreshAll(providers: List<DynamicApiProvider>): List<ProviderModel> = providers.flatMap(::refresh)

    private fun isFree(provider: DynamicApiProvider, model: DiscoveredApiModel): Boolean {
        if (!model.pricingKnown) return provider.providerFreeTier
        return isZero(model.inputPrice) && isZero(model.outputPrice)
    }

    private fun isZero(value: String?): Boolean = value?.trim()?.let { it == "0" || it == "0.0" || it == "0.00" } == true
}

private fun JSONObject.optIntOrNull(name: String): Int? = if (has(name) && !isNull(name)) optInt(name) else null
