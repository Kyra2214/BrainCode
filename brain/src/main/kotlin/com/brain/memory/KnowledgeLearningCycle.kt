package com.brain.memory

/** Ciclo que separa candidato externo de conhecimento validado. */
class KnowledgeLearningCycle(private val memory: KnowledgeMemory? = null, private val conversationId: String? = null) {
    private val scopedMemory: KnowledgeMemory get() = memory ?: KnowledgeMemoryRegistry.current(conversationId)
    fun recall(problem: String, scope: KnowledgeScope = KnowledgeScope.GLOBAL, ownerId: String? = null, projectId: String? = null): KnowledgeEntry? =
        scopedMemory.findValidated(problem, scope = scope, ownerId = ownerId, projectId = projectId)

    fun recall(
        problem: String,
        intent: String,
        entities: Map<String, String>,
        scope: KnowledgeScope = KnowledgeScope.GLOBAL,
        ownerId: String? = null,
        projectId: String? = null
    ): KnowledgeEntry? = scopedMemory.findValidatedStructured(problem, intent, entities, scope = scope, ownerId = ownerId, projectId = projectId)

    fun observeExternal(
        problem: String,
        answer: String,
        source: KnowledgeSource?,
        retrievalHints: List<String>,
        tags: List<String>,
        providerConfidence: Double = 0.0,
        citations: List<KnowledgeCitation> = emptyList(),
        scope: KnowledgeScope = KnowledgeScope.GLOBAL,
        ownerId: String? = null,
        projectId: String? = null,
        expiresAtEpochMs: Long? = null,
        provenance: KnowledgeProvenance = KnowledgeProvenance.LOCAL_BUILTIN
    ): KnowledgeEntry = scopedMemory.saveCandidate(
        KnowledgeEntry(
            problem = problem,
            answer = answer,
            source = source,
            retrievalHints = retrievalHints.distinct(),
            tags = tags.distinct(),
            confidence = providerConfidence.coerceIn(0.0, 1.0),
            validated = false,
            citations = citations.distinctBy { it.uri + "|" + it.quote },
            scope = scope,
            ownerId = ownerId,
            projectId = projectId,
            expiresAtEpochMs = expiresAtEpochMs,
            provenance = provenance,
            normalizedQuery = problem.trim().lowercase().replace(Regex("\\s+"), " ")
        )
    )

    /** O Critic chama isto somente depois de validar a resposta. */
    fun confirm(knowledgeId: String, confidence: Double, source: KnowledgeSource? = null): KnowledgeEntry? =
        scopedMemory.confirm(knowledgeId, confidence, source)

    /** O Critic pode substituir uma resposta errada sem perder a trilha original. */
    fun correct(knowledgeId: String, correctedAnswer: String, confidence: Double): KnowledgeEntry? =
        scopedMemory.recordCorrection(knowledgeId, correctedAnswer, confidence)
}
