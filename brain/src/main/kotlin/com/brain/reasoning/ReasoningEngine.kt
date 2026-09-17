package com.brain.reasoning

import com.brain.prompt.PromptDomain

/** Tipo de tarefa reconhecido antes de planejar qualquer capability. */
enum class ReasoningIntent {
    CREATE_PROMPT,
    REFINE_PROMPT,
    IMPROVE_PROMPT,
    BUILD_CODE,
    GENERAL_TEXT
}

data class Requirement(
    val text: String,
    val explicit: Boolean = true
)

data class ReasoningState(
    val objective: String,
    val intent: ReasoningIntent,
    val domain: PromptDomain,
    val requirements: List<Requirement>,
    val assumptions: List<String>,
    val missing: List<String>,
    val slots: List<RequirementSlot> = emptyList(),
    val constraints: List<RequirementConstraint> = emptyList(),
    val dependencies: List<RequirementDependency> = emptyList()
) {
    val canProceedLocally: Boolean get() = missing.isEmpty()
}

/** Interpretação determinística: não executa ações, não usa LLM e não inventa requisitos críticos. */
class ReasoningEngine {
    private val requirementDiscovery = RequirementDiscovery()
    private val assumptionManager = AssumptionManager()

    fun analyze(request: String): ReasoningState {
        val objective = request.trim()
        require(objective.isNotBlank()) { "objetivo não pode ser vazio" }
        val lower = objective.lowercase()
        val domain = PromptDomain.classificar(objective)
        val intent = when {
            isRefinement(lower) -> if (isExplicitImprovement(lower)) ReasoningIntent.IMPROVE_PROMPT else ReasoningIntent.REFINE_PROMPT
            domain == PromptDomain.CODIGO -> ReasoningIntent.BUILD_CODE
            lower.contains("prompt") -> ReasoningIntent.CREATE_PROMPT
            else -> ReasoningIntent.GENERAL_TEXT
        }
        val discovered = requirementDiscovery.discover(objective, domain)
        val decisions = assumptionManager.decide(objective, domain, discovered.explicit)
        val missing = if (isRefinement(lower)) emptyList() else discovered.missing + decisions.blocked
        return ReasoningState(
            objective,
            intent,
            domain,
            discovered.explicit,
            decisions.assumptions,
            missing.distinct(),
            discovered.slots,
            discovered.constraints,
            discovered.dependencies
        )
    }

    private fun isRefinement(lower: String): Boolean = lower.containsAny(
        "mude", "muda", "troque", "troca", "adicione", "adiciona", "substitua", "substitui", "melhore", "melhora", "otimize", "otimiza"
    )

    private fun isExplicitImprovement(lower: String): Boolean = lower.containsAny("melhore", "melhora", "otimize", "otimiza", "mais profissional", "reformule")

    private fun String.containsAny(vararg terms: String): Boolean = terms.any { it in this }
}
