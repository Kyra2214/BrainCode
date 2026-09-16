package com.sandbox.app

import android.content.Context
import com.brain.skill.SkillManifest
import com.brain.skill.TrustLevel
import org.json.JSONObject

/** Entry from the app-local skills catalog. No registration authority is implied by this data. */
data class SkillCatalogoEntry(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val category: String,
    val capabilities: Set<String>,
    val triggers: Set<String>,
    val requiredPermissions: Set<String>,
    val riskClass: String,
    val sandboxRequired: Boolean,
    val tools: Set<String>,
    val preferredAgents: Set<String>,
    val preferredProviders: Set<String>,
    val inputSchema: String?,
    val outputSchema: String?,
    val validation: Set<String>,
    val trustLevel: String,
    val enabled: Boolean
) {
    fun paraManifest(): SkillManifest = SkillManifest(
        id = id,
        name = name,
        version = version,
        description = description,
        category = category,
        capabilities = capabilities,
        triggers = triggers,
        requiredPermissions = requiredPermissions,
        trustLevel = when (trustLevel.uppercase()) {
            "CORE" -> TrustLevel.CORE
            "VERIFIED" -> TrustLevel.VERIFIED
            "COMMUNITY" -> TrustLevel.COMMUNITY
            else -> TrustLevel.UNTRUSTED
        },
        enabled = enabled,
        // The catalog is packaged inside this app, not downloaded at runtime.
        sourceId = "brain-builtin"
    )
}

object SkillsCatalogoLoader {
    fun load(context: Context): List<SkillCatalogoEntry> = context.assets.open("skills_catalog.json").use { stream ->
        val root = JSONObject(stream.bufferedReader(Charsets.UTF_8).use { it.readText() })
        val skills = root.getJSONArray("skills")
        buildList {
            for (index in 0 until skills.length()) {
                runCatching { skills.getJSONObject(index).toEntry() }
                    .onSuccess { add(it) }
            }
        }
    }

    private fun JSONObject.toEntry(): SkillCatalogoEntry = SkillCatalogoEntry(
        id = getString("id"),
        name = getString("name"),
        version = getString("version"),
        description = optString("description"),
        category = optString("category", "general"),
        capabilities = stringSet("capabilities"),
        triggers = stringSet("triggers"),
        requiredPermissions = stringSet("required_permissions"),
        riskClass = optString("risk_class", "UNKNOWN"),
        sandboxRequired = optBoolean("sandbox_required", true),
        tools = stringSet("tools"),
        preferredAgents = stringSet("preferred_agents"),
        preferredProviders = stringSet("preferred_providers"),
        inputSchema = optNullableString("input_schema"),
        outputSchema = optNullableString("output_schema"),
        validation = stringSet("validation"),
        trustLevel = optString("trust_level", "UNTRUSTED"),
        enabled = optBoolean("enabled", false)
    )

    private fun JSONObject.stringSet(key: String): Set<String> {
        val values = optJSONArray(key) ?: return emptySet()
        return buildSet { for (index in 0 until values.length()) add(values.getString(index)) }
    }

    private fun JSONObject.optNullableString(key: String): String? = if (has(key) && !isNull(key)) optString(key) else null
}
