package com.brain.capability

import com.brain.execution.RiskClass

/** Primeiros agents declarativos previstos no plano; execução continua bounded e policy-gated. */
object BuiltInAgentDefinitions {
    fun researchAgent(): CapabilityDefinition = definition(
        id = "agent.research",
        name = "ResearchAgent",
        description = "Agente bounded para pesquisa com evidência e provenance",
        provided = setOf("research.web", "research.evidence"),
        risk = RiskClass.MEDIUM,
        web = true
    )

    fun codeAgent(): CapabilityDefinition = definition(
        id = "agent.code",
        name = "CodeAgent",
        description = "Agente bounded para análise, implementação e testes em Sandbox",
        provided = setOf("code.edit", "code.test"),
        risk = RiskClass.LOW,
        code = true
    )

    fun boundedSpecialists(): List<CapabilityDefinition> = listOf(
        definition("agent.requirements", "RequirementsAgent", "Extrai requisitos e lacunas", setOf("requirements.resolve"), RiskClass.LOW),
        definition("agent.architecture", "ArchitectureAgent", "Propõe arquitetura bounded", setOf("architecture.design"), RiskClass.MEDIUM),
        definition("agent.roadmap", "RoadmapAgent", "Organiza roadmap e tarefas", setOf("roadmap.plan"), RiskClass.LOW),
        definition("agent.ui", "UIAgent", "Especialista bounded de interface", setOf("ui.design"), RiskClass.MEDIUM),
        definition("agent.backend", "BackendAgent", "Especialista bounded de backend", setOf("backend.design"), RiskClass.MEDIUM),
        definition("agent.database", "DatabaseAgent", "Especialista bounded de dados", setOf("database.design"), RiskClass.MEDIUM),
        definition("agent.security", "SecurityAgent", "Revisa segurança e permissões", setOf("security.review"), RiskClass.HIGH),
        definition("agent.test", "TestAgent", "Planeja e valida testes", setOf("test.plan"), RiskClass.LOW),
        definition("agent.review", "ReviewAgent", "Revisa findings e qualidade", setOf("review.execute"), RiskClass.MEDIUM),
        definition("agent.integration", "IntegrationAgent", "Integra componentes bounded", setOf("integration.execute"), RiskClass.MEDIUM),
        definition("agent.release", "ReleaseAgent", "Prepara release e entrega", setOf("release.package"), RiskClass.MEDIUM)
    )

    private fun definition(id: String, name: String, description: String, provided: Set<String>, risk: RiskClass, web: Boolean = false, code: Boolean = false) = CapabilityDefinition(
        id = id, name = name, description = description, category = CapabilityCategory.AGENT,
        ownerId = "brain-builtin", origin = "brain-builtin", providedCapabilities = provided,
        risk = risk, supportsWeb = web, supportsCode = code, availability = CapabilityAvailability.AVAILABLE,
        reliability = .8, quality = .8, provenance = listOf(CapabilityProvenance("brain-builtin", "agent-definition"))
    )
}
