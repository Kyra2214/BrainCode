package com.brain.reasoning

import com.brain.prompt.PromptDomain
import com.brain.prompt.PromptQualityScore
import com.brain.prompt.PromptQualityValidator
import java.util.Locale

data class CritiqueResult(
    val score: PromptQualityScore,
    val attendedRequirements: List<String>,
    val missingRequirements: List<String>,
    val inventedAssumptions: List<String> = emptyList()
) {
    val accepted: Boolean get() = missingRequirements.isEmpty() && !score.abaixoDoPadrao
}

/** Crítica verificável: compara requisitos explícitos com o resultado, nunca opinião do gerador. */
class SelfCritic(
    private val validator: (String, String, PromptDomain) -> PromptQualityScore = PromptQualityValidator::validar
) {
    fun evaluate(state: ReasoningState, result: String): CritiqueResult {
        val score = validator(state.objective, result, state.domain)
        val lower = result.lowercase(Locale.ROOT)
        val attended = state.requirements
            .filter { requirementPresent(it.text, lower) }
            .map { it.text }
        val missing = state.requirements
            .filterNot { requirementPresent(it.text, lower) }
            .map { it.text }
        return CritiqueResult(score, attended, missing)
    }

    private fun requirementPresent(requirement: String, result: String): Boolean {
        val normalized = requirement.lowercase(Locale.ROOT)
        if (normalized in result) return true
        val tokens = normalized.split(Regex("[^\\p{L}\\p{Nd}]+" )).filter { it.length >= 4 }
        return tokens.isNotEmpty() && tokens.all { it in result }
    }
}
