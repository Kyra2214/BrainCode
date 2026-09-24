package com.brain.retrieval

/** Ordem econômica e arquitetural obrigatória do retrieval. */
enum class RetrievalLayer(val priority: Int) {
    MEMORY(0),
    KNOWLEDGE(1),
    SKILLS(2),
    PROMPT_LIBRARY(3),
    WORKFLOWS(4),
    TOOLS(5),
    AGENTS(6),
    APIS(7)
}

data class RetrievalQuery(
    val objective: String,
    val capability: String? = null,
    val context: Map<String, String> = emptyMap()
) {
    init {
        require(objective.isNotBlank()) { "objetivo do retrieval é obrigatório" }
        require(context.keys.none { it.isBlank() }) { "chave de contexto não pode ser vazia" }
    }
}

data class RetrievalHit(
    val id: String,
    val layer: RetrievalLayer,
    val content: String,
    val confidence: Double,
    val validated: Boolean,
    val provenance: List<String> = emptyList()
) {
    init {
        require(id.isNotBlank()) { "id do hit é obrigatório" }
        require(content.isNotBlank()) { "conteúdo do hit é obrigatório" }
        require(confidence in 0.0..1.0) { "confidence deve estar entre 0 e 1" }
        require(provenance.none { it.isBlank() }) { "proveniência não pode ser vazia" }
    }
}

fun interface RetrievalLookup {
    fun find(query: RetrievalQuery): List<RetrievalHit>
}

data class RetrievalSource(
    val id: String,
    val layer: RetrievalLayer,
    val lookup: RetrievalLookup,
    val validatedOnly: Boolean = layer == RetrievalLayer.MEMORY || layer == RetrievalLayer.KNOWLEDGE || layer == RetrievalLayer.SKILLS
) {
    init { require(id.isNotBlank()) { "id da fonte é obrigatório" } }
}

enum class RetrievalStatus { FOUND, NOT_FOUND }

data class RetrievalResult(
    val status: RetrievalStatus,
    val query: RetrievalQuery,
    val hit: RetrievalHit? = null,
    val attemptedLayers: List<RetrievalLayer> = emptyList(),
    val attemptedSources: List<String> = emptyList()
)

/**
 * Orquestrador de retrieval progressivo. A primeira resposta válida de uma
 * camada encerra a busca; em particular, uma memória/skill validada impede
 * consulta às fontes mais caras, inclusive APIs.
 */
class Retrieval(sources: Iterable<RetrievalSource>) {
    private val orderedSources = sources
        .sortedWith(compareBy<RetrievalSource> { it.layer.priority }.thenBy { it.id })
        .also { require(it.map(RetrievalSource::id).distinct().size == it.size) { "fontes de retrieval duplicadas" } }

    fun retrieve(query: RetrievalQuery): RetrievalResult {
        val attemptedLayers = linkedSetOf<RetrievalLayer>()
        val attemptedSources = mutableListOf<String>()
        for (source in orderedSources) {
            attemptedLayers += source.layer
            attemptedSources += source.id
            val hit = source.lookup.find(query)
                .asSequence()
                .filter { it.layer == source.layer }
                .filter { !source.validatedOnly || it.validated }
                .filter { it.confidence > 0.0 }
                .sortedByDescending { it.confidence }
                .firstOrNull()
            if (hit != null) {
                return RetrievalResult(RetrievalStatus.FOUND, query, hit, attemptedLayers.toList(), attemptedSources.toList())
            }
        }
        return RetrievalResult(RetrievalStatus.NOT_FOUND, query, attemptedLayers = attemptedLayers.toList(), attemptedSources = attemptedSources)
    }
}
