package com.brain.memory

import com.brain.skill.SkillManifest

/** Evidência bruta; sua presença não implica verdade nem validação. */
data class KnowledgeEvidence(
    val evidenceId: String,
    val problem: String,
    val answer: String,
    val source: KnowledgeSource?,
    val retrievalHints: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val providerConfidence: Double = 0.0,
    val provenance: List<String> = emptyList()
) {
    init {
        require(evidenceId.isNotBlank()) { "evidenceId é obrigatório" }
        require(problem.isNotBlank()) { "problema da evidência é obrigatório" }
        require(provenance.none { it.isBlank() }) { "proveniência não pode ser vazia" }
    }
}

enum class KnowledgeCompilationStatus { CANDIDATE, VALIDATED, REJECTED, UNCERTAIN }

data class KnowledgeCompilation(
    val evidence: KnowledgeEvidence,
    val entry: KnowledgeEntry,
    val verdict: KnowledgeCriticVerdict,
    val status: KnowledgeCompilationStatus
)

data class SkillCandidate(
    val knowledgeId: String,
    val manifest: SkillManifest,
    val evidenceIds: List<String>
)

/**
 * Compilador de conhecimento: evidence → candidate → Critic → validated
 * knowledge. A promoção de um procedimento para Skill pertence ao
 * SkillValidator, que executa o candidato em sandbox, roda testes e passa
 * pelo critic da própria Skill antes de registrar.
 */
class KnowledgeCompiler(
    private val cycle: KnowledgeLearningCycle,
    private val critic: KnowledgeCritic
) {
    fun compile(evidence: KnowledgeEvidence): KnowledgeCompilation {
        val entry = cycle.observeExternal(
            problem = evidence.problem,
            answer = evidence.answer,
            source = evidence.source,
            retrievalHints = evidence.retrievalHints + evidence.provenance,
            tags = evidence.tags,
            providerConfidence = evidence.providerConfidence
        )
        val verdict = critic.evaluate(entry)
        val status = when (verdict.decision) {
            KnowledgeCriticDecision.ACCEPT -> KnowledgeCompilationStatus.VALIDATED
            KnowledgeCriticDecision.REJECT -> KnowledgeCompilationStatus.REJECTED
            KnowledgeCriticDecision.UNCERTAIN -> KnowledgeCompilationStatus.UNCERTAIN
        }
        val finalEntry = if (status == KnowledgeCompilationStatus.VALIDATED) {
            cycle.confirm(entry.id, verdict.confidence, evidence.source) ?: entry
        } else {
            entry
        }
        return KnowledgeCompilation(evidence, finalEntry, verdict, status)
    }

    fun proposeSkill(compilation: KnowledgeCompilation, manifest: SkillManifest): SkillCandidate? {
        if (compilation.status != KnowledgeCompilationStatus.VALIDATED || !compilation.entry.validated) return null
        require(manifest.capabilities.isNotEmpty()) { "Skill candidate deve declarar capabilities" }
        return SkillCandidate(compilation.entry.id, manifest, listOf(compilation.evidence.evidenceId))
    }

}
