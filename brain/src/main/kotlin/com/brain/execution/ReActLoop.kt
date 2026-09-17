package com.brain.execution

import com.brain.gateway.PreActionCheckResult

data class OperationalStep(val id: String, val description: String)
data class Observation(val stepId: String, val success: Boolean, val detail: String)
data class OperationalState(
    val objective: String,
    val completed: List<String> = emptyList(),
    val observations: List<Observation> = emptyList(),
    val nextStep: OperationalStep? = null
)

data class ReActResult(
    val state: OperationalState,
    val stopped: Boolean,
    val stopReason: String? = null
)

/** Loop operacional limitado: nunca executa o próximo passo sem observar o anterior. */
class ReActLoop(private val maxSteps: Int = 10) {
    init { require(maxSteps in 1..50) { "limite operacional deve estar entre 1 e 50" } }

    fun run(
        objective: String,
        steps: List<OperationalStep>,
        preCheck: (OperationalStep) -> PreActionCheckResult,
        execute: (OperationalStep) -> Observation
    ): ReActResult {
        require(objective.isNotBlank()) { "objetivo operacional é obrigatório" }
        var state = OperationalState(objective, nextStep = steps.firstOrNull())
        var executed = 0
        for (step in steps) {
            if (executed >= maxSteps) return ReActResult(state.copy(nextStep = step), true, "limite de passos atingido")
            val check = preCheck(step)
            if (!check.allowed) return ReActResult(state.copy(nextStep = step), true, "pre-check bloqueou: ${check.reasons.joinToString("; ")}")
            val observation = execute(step)
            executed++
            state = state.copy(
                completed = if (observation.success) state.completed + step.id else state.completed,
                observations = state.observations + observation,
                nextStep = if (observation.success) steps.dropWhile { it.id != step.id }.drop(1).firstOrNull() else step
            )
            if (!observation.success) return ReActResult(state, true, "observação indicou falha em ${step.id}")
        }
        return ReActResult(state.copy(nextStep = null), false)
    }
}
