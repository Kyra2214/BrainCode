package com.brain.reasoning

import com.brain.prompt.LocalPromptCreatorAgent
import com.brain.prompt.PromptCriado

/** Resultado de revisão com histórico curto para auditoria e prevenção de loops. */
data class RevisionResult(
    val prompt: String,
    val critique: CritiqueResult,
    val revisions: Int,
    val exhausted: Boolean
)

/** Revisa somente enquanto a crítica encontrar falha e nunca ultrapassa o limite definido. */
class RevisionEngine(
    private val creator: LocalPromptCreatorAgent = LocalPromptCreatorAgent(),
    private val critic: SelfCritic = SelfCritic(),
    private val maxRevisions: Int = 3
) {
    init { require(maxRevisions in 1..3) { "limite de revisões deve estar entre 1 e 3" } }

    fun revise(state: ReasoningState, initialPrompt: String): RevisionResult {
        var current = initialPrompt.trim()
        var critique = critic.evaluate(state, current)
        var count = 0
        while (!critique.accepted && count < maxRevisions) {
            val weakPoints = critique.score.pontosFracos + if (critique.missingRequirements.isNotEmpty()) setOf("presença de elementos") else emptySet()
            val revised: PromptCriado = creator.melhorarLocalmente(current, state.objective, weakPoints)
            if (revised.texto.trim() == current) break
            current = revised.texto.trim()
            count++
            critique = critic.evaluate(state, current)
        }
        return RevisionResult(current, critique, count, !critique.accepted && count >= maxRevisions)
    }
}
