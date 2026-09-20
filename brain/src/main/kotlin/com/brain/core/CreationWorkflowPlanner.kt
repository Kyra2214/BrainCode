package com.brain.core

import com.brain.secretary.CreatePhase
import com.brain.secretary.OrderIntent

/**
 * Compõe o workflow da Porta 3 sem provider: roadmap, tarefas e especialista
 * são dados verificáveis que o controller pode registrar e executar sob Policy.
 */
data class SpecialistAssignment(val taskId: String, val specialistId: String, val capability: String)
data class CreationWorkflowPlan(
    val roadmap: Roadmap,
    val tasks: List<Tarefa>,
    val assignments: List<SpecialistAssignment>
)

object CreationWorkflowPlanner {
    fun build(intent: OrderIntent, requirements: List<String> = emptyList()): CreationWorkflowPlan {
        require(intent.phase >= CreatePhase.APPROVED) { "workflow de criação exige aprovação" }
        val objective = intent.originalPrompt.trim()
        val projectIntent = ProjectIntent(
            originalRequest = UserRequest(objective),
            projectType = inferProjectType(objective),
            platform = inferPlatform(objective),
            complexity = if (objective.length > 160) Complexity.ALTA else Complexity.MEDIA,
            areasInvolvidas = listOf("requirements", "architecture", "implementation", "tests", "delivery"),
            podeResolverLocal = true
        )
        val tarefas = listOf(
            Tarefa("requirements", "Consolidar requisitos e pendências: ${requirements.joinToString("; ").ifBlank { "nenhuma pendência explícita" }}"),
            Tarefa("architecture", "Definir arquitetura do ${projectIntent.projectType}"),
            Tarefa("implementation", "Implementar o escopo aprovado do ${projectIntent.projectType}"),
            Tarefa("integration", "Integrar componentes e validar contratos"),
            Tarefa("tests", "Executar testes, revisão e readiness"),
            Tarefa("delivery", "Preparar resumo, Git autorizado e ZIP local")
        )
        val fases = listOf(
            Fase("DISCUSSION", listOf(Modulo("requirements", listOf(Submodulo("discovery", listOf(tarefas[0])))))),
            Fase("ARCHITECTURE", listOf(Modulo("design", listOf(Submodulo("architecture", listOf(tarefas[1])))))),
            Fase("EXECUTION", listOf(Modulo("implementation", listOf(Submodulo("code", listOf(tarefas[2])))))),
            Fase("INTEGRATION", listOf(Modulo("integration", listOf(Submodulo("contracts", listOf(tarefas[3])))))),
            Fase("TESTS", listOf(Modulo("quality", listOf(Submodulo("validation", listOf(tarefas[4])))))),
            Fase("DELIVERY", listOf(Modulo("release", listOf(Submodulo("package", listOf(tarefas[5]))))))
        )
        val assignments = listOf(
            SpecialistAssignment("requirements", "agent.requirements", "requirements.resolve"),
            SpecialistAssignment("architecture", "agent.architecture", "architecture.design"),
            SpecialistAssignment("implementation", "agent.code", "code.edit"),
            SpecialistAssignment("integration", "agent.integration", "integration.execute"),
            SpecialistAssignment("tests", "agent.test", "test.plan"),
            SpecialistAssignment("delivery", "agent.release", "release.package")
        )
        return CreationWorkflowPlan(Roadmap(projectIntent, fases), tarefas, assignments)
    }

    private fun inferProjectType(objective: String): String = when {
        objective.contains("site", true) || objective.contains("web", true) -> "site web"
        objective.contains("api", true) || objective.contains("backend", true) -> "serviço backend"
        objective.contains("app", true) || objective.contains("aplicativo", true) -> "aplicativo"
        else -> "software"
    }

    private fun inferPlatform(objective: String): String? = when {
        objective.contains("android", true) -> "android"
        objective.contains("ios", true) -> "ios"
        objective.contains("web", true) || objective.contains("site", true) -> "web"
        else -> null
    }
}
