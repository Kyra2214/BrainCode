package com.brain.router

import org.json.JSONArray
import org.json.JSONObject

/**
 * Carrega o catálogo operacional de APIs gratuitas.
 *
 * O Brain só pode receber modelos com access FREE_TIER ou FREE_PERMANENT.
 * Créditos promocionais e PAYG são deliberadamente descartados aqui como
 * segunda barreira, mesmo que alguém acrescente um provider pago ao JSON.
 */
object ApiCatalogLoader {
    private val FREE_ACCESS = setOf("FREE_TIER", "FREE_PERMANENT")

    fun fromJson(json: String): List<ProviderModel> {
        val root = JSONObject(json)
        val providers = root.getJSONArray("providers")
        val resultado = mutableListOf<ProviderModel>()

        for (i in 0 until providers.length()) {
            val provider = providers.getJSONObject(i)
            val providerId = provider.getString("id")
            val models = provider.optJSONArray("models") ?: continue

            for (j in 0 until models.length()) {
                val modelo = models.getJSONObject(j)
                val access = modelo.optString("access", "FREE_TIER")
                if (access !in FREE_ACCESS) continue
                resultado += ProviderModel(
                    providerId = providerId,
                    modeloId = modelo.getString("id"),
                    papeisSugeridos = papeisPara(modelo.optJSONArray("capabilities")),
                    janela = JanelaLimite(),
                    contextoMaximoTokens = null
                )
            }
        }
        return resultado
    }

    /**
     * Heurística inicial de mapeamento capability -> papel do pipeline.
     * TODO (Fase D/G): substituir por dado real de uso (ExperienceMemory)
     * em vez de adivinhar a partir da string de capability declarada.
     */
    private fun papeisPara(capabilities: JSONArray?): List<PapelPipeline> {
        if (capabilities == null || capabilities.length() == 0) return PapelPipeline.values().toList()
        val tags = (0 until capabilities.length()).map { capabilities.getString(it).lowercase() }.toSet()

        val papeis = mutableSetOf<PapelPipeline>()
        if ("reasoning" in tags) papeis += PapelPipeline.PLANEJAMENTO
        if ("chat" in tags) papeis += PapelPipeline.ESCRITA_DE_PROMPT
        if ("coding" in tags || "agent" in tags) papeis += PapelPipeline.EXECUCAO_CODIGO

        return if (papeis.isEmpty()) PapelPipeline.values().toList() else papeis.toList()
    }
}
