package com.sandbox.app

import android.content.Context
import com.brain.memory.KnowledgeMemoryRegistry
import com.brain.router.ApiCatalogRegistry
import com.brain.router.DynamicApiProvider
import com.brain.router.DynamicFreeApiCatalog
import com.brain.router.DynamicFreeApiModelDiscovery
import com.brain.router.JanelaLimite
import com.brain.router.PapelPipeline
import com.brain.router.ProviderModel
import org.json.JSONObject

/**
 * Um modelo específico oferecido por um provider.
 * A lista presente no JSON é apenas bootstrap: o Brain consulta a API do
 * provider em runtime e substitui os modelos quando a descoberta funciona.
 */
data class ApiProviderModel(
    val id: String,
    val name: String,
    val capabilities: List<String>,
    val access: String,
    val endpoint: String,
    val requiresKey: Boolean
)

data class ApiProvider(
    val id: String,
    val name: String,
    val region: String,
    val officialUrl: String,
    val documentationUrl: String,
    val modelsEndpoint: String?,
    val models: List<ApiProviderModel>
)

/**
 * Carrega o catálogo operacional e instala o mesmo catálogo no Router do Brain.
 * Somente FREE_TIER/FREE_PERMANENT entram. IDs de modelos não são uma allowlist:
 * são apenas seed para o primeiro uso/offline; depois a API é a fonte de verdade.
 */
object ApiKeyCatalogLoader {
    private val FREE_ACCESS = setOf("FREE_TIER", "FREE_PERMANENT")

    fun load(context: Context): List<ApiProvider> {
        val json = context.assets.open("ai_api_catalog.json").bufferedReader().use { it.readText() }
        val root = JSONObject(json)
        val providersJson = root.getJSONArray("providers")
        val providers = buildList {
            for (index in 0 until providersJson.length()) {
                val providerObj = providersJson.getJSONObject(index)
                val modelsJson = providerObj.getJSONArray("models")
                val models = buildList {
                    for (modelIndex in 0 until modelsJson.length()) {
                        val modelObj = modelsJson.getJSONObject(modelIndex)
                        val access = modelObj.optString("access", "FREE_TIER")
                        if (access !in FREE_ACCESS) continue
                        val capabilitiesJson = modelObj.optJSONArray("capabilities")
                        val capabilities = buildList {
                            if (capabilitiesJson != null) {
                                for (capIndex in 0 until capabilitiesJson.length()) add(capabilitiesJson.getString(capIndex))
                            }
                        }
                        add(
                            ApiProviderModel(
                                id = modelObj.getString("id"),
                                name = modelObj.getString("name"),
                                capabilities = capabilities,
                                access = access,
                                endpoint = modelObj.getString("endpoint"),
                                requiresKey = modelObj.optBoolean("requiresKey", true)
                            )
                        )
                    }
                }
                if (models.isNotEmpty()) {
                    add(
                        ApiProvider(
                            id = providerObj.getString("id"),
                            name = providerObj.getString("name"),
                            region = providerObj.optString("region", ""),
                            officialUrl = providerObj.getString("officialUrl"),
                            documentationUrl = providerObj.optString("documentationUrl", providerObj.getString("officialUrl")),
                            modelsEndpoint = providerObj.optString("modelsEndpoint").takeIf { it.isNotBlank() },
                            models = models
                        )
                    )
                }
            }
        }

        KnowledgeMemoryRegistry.install(AndroidKnowledgeMemory(context))
        installBrainCatalog(providers, context)
        return providers
    }

    private fun installBrainCatalog(providers: List<ApiProvider>, context: Context) {
        val keyStore = ApiKeyStore(context)
        val dynamicProviders = providers.mapNotNull { provider ->
            val endpoint = provider.models.firstOrNull()?.endpoint ?: return@mapNotNull null
            val modelsEndpoint = provider.modelsEndpoint ?: when {
                endpoint.endsWith("/models") -> endpoint
                endpoint.endsWith("/") -> endpoint + "models"
                else -> endpoint + "/models"
            }
            DynamicApiProvider(
                providerId = provider.id,
                modelsEndpoint = modelsEndpoint,
                papers = provider.models.flatMap { model ->
                    buildList {
                        if ("chat" in model.capabilities) add(PapelPipeline.ESCRITA_DE_PROMPT)
                        if ("coding" in model.capabilities || "agent" in model.capabilities) add(PapelPipeline.EXECUCAO_CODIGO)
                        if ("reasoning" in model.capabilities) add(PapelPipeline.PLANEJAMENTO)
                    }
                }.distinct().ifEmpty { PapelPipeline.entries.toList() },
                providerFreeTier = true
            )
        }
        if (dynamicProviders.isEmpty()) return

        val seed = providers.flatMap { provider ->
            provider.models.map { model ->
                ProviderModel(
                    providerId = provider.id,
                    modeloId = model.id,
                    papeisSugeridos = dynamicProviders.first { it.providerId == provider.id }.papers,
                    janela = JanelaLimite()
                )
            }
        }
        val discovery = DynamicFreeApiModelDiscovery(apiKey = { providerId -> keyStore.get(providerId) })
        ApiCatalogRegistry.install(
            DynamicFreeApiCatalog(
                seed = seed,
                providers = dynamicProviders,
                discovery = discovery,
                refreshEveryMs = 5 * 60 * 1000L
            )
        )
    }
}
