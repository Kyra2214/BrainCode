package com.brain.memory

enum class KnowledgeArtifactKind { KNOWLEDGE, SKILL, PROMPT, WORKFLOW, RULE }

data class KnowledgeArtifactCandidate(
    val knowledgeId: String,
    val kind: KnowledgeArtifactKind,
    val content: String,
    val evidenceIds: List<String>,
    val validated: Boolean
) {
    init {
        require(knowledgeId.isNotBlank() && content.isNotBlank()) { "artefato exige id e conteúdo" }
        require(evidenceIds.isNotEmpty()) { "artefato exige evidência" }
    }
}

fun KnowledgeCompiler.proposeArtifact(
    compilation: KnowledgeCompilation,
    kind: KnowledgeArtifactKind,
    content: String
): KnowledgeArtifactCandidate? {
    if (compilation.status != KnowledgeCompilationStatus.VALIDATED || !compilation.entry.validated) return null
    return KnowledgeArtifactCandidate(
        knowledgeId = compilation.entry.id,
        kind = kind,
        content = content,
        evidenceIds = listOf(compilation.evidence.evidenceId),
        validated = false
    )
}
