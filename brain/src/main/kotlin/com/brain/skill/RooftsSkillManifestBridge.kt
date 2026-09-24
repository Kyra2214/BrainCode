package com.brain.skill

/**
 * Converte metadados de uma Skill de conteúdo em manifesto do registry sem ativá-la. O registry
 * passa a conhecer a origem, hash e permissões; a ativação continua exigindo decisão explícita.
 */
object RooftsSkillManifestBridge {
    fun manifest(skill: RooftsSkill, enabled: Boolean = false): SkillManifest = SkillManifest(
        id = "roofts.${skill.id}",
        name = skill.id,
        version = "0.6.0",
        description = skill.description,
        category = "methodology",
        capabilities = skill.requiredCapabilities.ifEmpty { setOf("skill.context") },
        triggers = skill.triggers,
        requiredPermissions = skill.requiredPermissions,
        trustLevel = TrustLevel.VERIFIED,
        enabled = enabled,
        sourceId = skill.origin,
        license = skill.license,
        contentHash = skill.contentHash,
        exclusions = skill.exclusions,
        resources = skill.resources,
        tools = skill.tools,
        networkPolicy = skill.networkPolicy
    )

    fun registerDiscovered(skill: RooftsSkill, registry: SkillRegistry): SkillRecord =
        registry.register(manifest(skill, enabled = false))
}
