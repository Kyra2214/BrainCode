package com.brain.reasoning

/** Contexto mínimo e ordenado que pode ser passado ao Planner sem carregar o histórico inteiro. */
data class ContextPack(
    val objective: String,
    val intent: ReasoningIntent,
    val domain: String,
    val requirements: List<String>,
    val assumptions: List<String>,
    val constraints: List<String>,
    val dependencies: List<String>,
    val missingRequirements: List<String>,
    val relevantHistory: List<String> = emptyList(),
    val decisions: List<String> = emptyList(),
    val knownErrors: List<String> = emptyList()
) {
    init { require(objective.isNotBlank()) { "ContextPack precisa de objetivo" } }

    fun compact(maxItemsPerSection: Int = 12): ContextPack {
        require(maxItemsPerSection > 0)
        return copy(
            requirements = requirements.distinct().take(maxItemsPerSection),
            assumptions = assumptions.distinct().take(maxItemsPerSection),
            constraints = constraints.distinct().take(maxItemsPerSection),
            dependencies = dependencies.distinct().take(maxItemsPerSection),
            relevantHistory = relevantHistory.distinct().take(maxItemsPerSection),
            decisions = decisions.distinct().take(maxItemsPerSection),
            knownErrors = knownErrors.distinct().take(maxItemsPerSection)
        )
    }
}

object ContextPackBuilder {
    fun from(state: ReasoningState, relevantHistory: List<String> = emptyList(), knownErrors: List<String> = emptyList()): ContextPack = ContextPack(
        objective = state.objective,
        intent = state.intent,
        domain = state.domain.name,
        requirements = state.requirements.map { it.text },
        assumptions = state.assumptions,
        constraints = state.constraints.map { it.text },
        dependencies = state.dependencies.map { "${it.requirement} depende de ${it.dependsOn}" },
        missingRequirements = state.missing,
        relevantHistory = relevantHistory,
        decisions = listOf("intent=${state.intent}", "canProceedLocally=${state.canProceedLocally}"),
        knownErrors = knownErrors
    ).compact()
}
