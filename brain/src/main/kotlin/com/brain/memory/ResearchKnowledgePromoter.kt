package com.brain.memory

import com.brain.research.ResearchRequest
import com.brain.research.ResearchRunResult
import com.brain.research.ResearchSecurityPolicy
import com.brain.research.SourceQuality
import java.time.Clock
import java.time.Duration

/** Promove somente sínteses com quality gate para o Knowledge Store oficial. */
class ResearchKnowledgePromoter(
    private val cycle: KnowledgeLearningCycle = KnowledgeLearningCycle(),
    private val clock: Clock = Clock.systemUTC()
) {
    fun promote(request: ResearchRequest, result: ResearchRunResult): KnowledgeEntry? {
        if (result.answer.isBlank() || result.citations.isEmpty()) return null
        if (ResearchSecurityPolicy.isUntrustedContent(result.answer)) return null
        if (result.sourceQuality != SourceQuality.HIGH && result.sourceQuality != SourceQuality.MEDIUM) return null
        if (result.confidence < 0.5) return null
        val now = clock.millis()
        val expires = now + if (isTemporal(request.query)) Duration.ofHours(6).toMillis() else Duration.ofDays(180).toMillis()
        val entry = cycle.observeExternal(
            problem = request.query,
            answer = result.answer,
            source = KnowledgeSource(type = "WEB_RESEARCH", providerId = result.execution.providerIds.joinToString(",")),
            retrievalHints = listOf(request.query) + result.citations.map { it.title },
            tags = listOf("web-research", "learned"),
            providerConfidence = result.confidence,
            citations = result.citations.map { KnowledgeCitation(it.url, it.excerpt.ifBlank { it.title }) },
            expiresAtEpochMs = expires,
            provenance = KnowledgeProvenance.WEB_RESEARCH
        )
        return cycle.confirm(entry.id, result.confidence, entry.source)
    }

    private fun isTemporal(query: String): Boolean = listOf(
        "atual", "agora", "hoje", "neste momento", "versão atual", "versao atual",
        "preço", "preco", "cotação", "cotacao", "temperatura", "placar", "último", "ultimo"
    ).any { query.contains(it, ignoreCase = true) }
}
