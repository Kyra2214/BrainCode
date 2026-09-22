package com.brain.research

import java.time.Instant

data class ResearchRequest(
    val query: String,
    val context: String = "",
    val constraints: ResearchConstraints = ResearchConstraints(),
    val freshness: Freshness = Freshness.BALANCED,
    val sourceRequirements: SourceRequirements = SourceRequirements(),
    val maxSteps: Int = 6,
    val networkPolicy: NetworkPolicy = NetworkPolicy.ALLOW_IF_AUTHORIZED,
    val requestId: String = "research-request",
    val conversationId: String? = null
) { init { require(query.isNotBlank()); require(maxSteps in 1..50) } }

data class ResearchConstraints(val maxSources: Int = 6, val maxContentCharsPerSource: Int = 12_000, val allowedDomains: Set<String> = emptySet(), val blockedDomains: Set<String> = emptySet(), val requireHttps: Boolean = true) {
    init { require(maxSources in 1..50); require(maxContentCharsPerSource in 500..100_000) }
}
enum class Freshness { CURRENT, RECENT, BALANCED, ANY }
enum class NetworkPolicy { OFFLINE_ONLY, ALLOW_IF_AUTHORIZED, REQUIRE_ONLINE }
data class SourceRequirements(val minimumDistinctDomains: Int = 1, val preferredSourceTypes: Set<SourceType> = setOf(SourceType.WEB), val requireCitations: Boolean = true)
enum class SourceType { WEB, ACADEMIC, NEWS, LOCAL_CACHE, BROWSER }
enum class SourceQuality { UNKNOWN, LOW, MEDIUM, HIGH, REJECTED }
data class ResearchCitation(val index: Int, val title: String, val url: String, val source: String, val excerpt: String = "")
data class ResearchEvidence(val id: String, val kind: String, val value: String, val sourceUrl: String? = null, val capturedAt: Instant = Instant.now())
data class FailedSource(val provider: String, val target: String, val diagnostic: String, val recoverable: Boolean = true)
data class ResearchExecutionMetadata(val runId: String, val startedAt: Instant, val finishedAt: Instant, val steps: Int, val providerIds: List<String>, val networkUsed: Boolean, val llmUsed: Boolean = false, val cacheHit: Boolean = false, val requestId: String = "research-request", val conversationId: String? = null)
data class ResearchRunResult(val answer: String, val data: Map<String, String> = emptyMap(), val sources: List<ResearchResult> = emptyList(), val evidence: List<ResearchEvidence> = emptyList(), val citations: List<ResearchCitation> = emptyList(), val sourceQuality: SourceQuality = SourceQuality.UNKNOWN, val confidence: Double = 0.0, val failedSources: List<FailedSource> = emptyList(), val execution: ResearchExecutionMetadata, val diagnostic: String? = null, val userMessage: String? = null)
fun interface SearchProvider { fun search(request: ResearchRequest): Result<List<ResearchResult>> }
fun interface FetchProvider { fun fetch(url: String, request: ResearchRequest): Result<ResearchResult> }
fun interface BrowserProvider { fun open(url: String, request: ResearchRequest): Result<String> }
fun interface ExtractionProvider { fun extract(content: String, request: ResearchRequest): Result<Map<String, String>> }
data class WebProviderSet(val search: List<SearchProvider> = emptyList(), val fetch: List<FetchProvider> = emptyList(), val browser: List<BrowserProvider> = emptyList(), val extraction: List<ExtractionProvider> = emptyList())
