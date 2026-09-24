package com.brain.skill

/** Skills candidatas do plano mestre; registro efetivo continua sendo explícito. */
object BuiltInSkillManifests {
    fun all(): List<SkillManifest> = listOf(
        manifest("research", "Pesquisa com evidência", setOf("research.web", "research.evidence")),
        manifest("github-research", "Pesquisa no GitHub", setOf("github.read", "research.evidence")),
        manifest("code-test", "Executar testes de código", setOf("code.test")),
        manifest("android-build", "Build Android", setOf("android.build")),
        manifest("project-audit", "Auditoria de projeto", setOf("project.read", "project.audit")),
        manifest("debug", "Diagnóstico determinístico", setOf("code.read", "code.debug"))
    )

    private fun manifest(id: String, name: String, capabilities: Set<String>) = SkillManifest(
        id = id,
        name = name,
        version = "1.0.0",
        description = "Skill candidata built-in do BrainCode",
        category = "braincode",
        capabilities = capabilities,
        trustLevel = TrustLevel.CORE,
        sourceId = "brain-builtin"
    )
}
