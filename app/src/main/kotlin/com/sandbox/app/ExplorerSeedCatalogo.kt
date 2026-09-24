package com.sandbox.app

import android.content.Context
import com.brain.discovery.ExplorerCandidate
import com.brain.discovery.ExplorerItemType
import com.brain.discovery.ExplorerRegion
import com.brain.discovery.OpenSourceStatus
import org.json.JSONObject

/** Curated discovery seed entry; all fields are revalidated by ExplorerCandidate. */
data class ExplorerSeedItem(
    val id: String,
    val name: String,
    val type: String,
    val region: String,
    val officialUrl: String,
    val capabilities: Set<String>,
    val openSource: String,
    val confidence: Double
) {
    fun paraCandidate(): ExplorerCandidate = ExplorerCandidate(
        id = id,
        name = name,
        type = enumOr(ExplorerItemType.TOOL, type),
        region = enumOr(ExplorerRegion.OTHER, region),
        officialUrl = officialUrl,
        description = "Catálogo local do Explorer Intelligence (${type.lowercase()}).",
        capabilities = capabilities,
        openSource = enumOr(OpenSourceStatus.UNKNOWN, openSource),
        sourcePriority = 1,
        confidence = confidence
    )

    private inline fun <reified T : Enum<T>> enumOr(fallback: T, value: String): T =
        runCatching { enumValueOf<T>(value.uppercase()) }.getOrDefault(fallback)
}

object ExplorerSeedLoader {
    fun load(context: Context): List<ExplorerSeedItem> = context.assets.open("explorer_china_seed.json").use { stream ->
        val root = JSONObject(stream.bufferedReader(Charsets.UTF_8).use { it.readText() })
        val items = root.getJSONArray("itens")
        buildList {
            for (index in 0 until items.length()) {
                runCatching { items.getJSONObject(index).toEntry() }
                    .onSuccess { add(it) }
            }
        }
    }

    private fun JSONObject.toEntry(): ExplorerSeedItem = ExplorerSeedItem(
        id = getString("id"),
        name = getString("nome"),
        type = optString("tipo", "TOOL"),
        region = optString("regiao", "OTHER"),
        officialUrl = getString("siteOficial"),
        capabilities = stringSet("capacidades"),
        openSource = optString("openSource", "UNKNOWN"),
        confidence = optDouble("confianca", 0.0)
    )

    private fun JSONObject.stringSet(key: String): Set<String> {
        val values = optJSONArray(key) ?: return emptySet()
        return buildSet { for (index in 0 until values.length()) add(values.getString(index)) }
    }
}
