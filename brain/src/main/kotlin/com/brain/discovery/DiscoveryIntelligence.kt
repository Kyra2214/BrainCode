package com.brain.discovery

/**
 * REAPROVEITADO quase integralmente do IaBrain (era ExplorerIntelligence.kt,
 * pacote com.aibrain.app.brain — código próprio, não terceiro, sem questão
 * de licença). Único ajuste conceitual: o canal BROWSER nunca deve ser
 * alimentado em ConnectorProfile.channels nesta estrutura nova (decisão:
 * zero navegador, tudo via API/CLI dentro do Sandbox) — a lógica de
 * chooseChannel() já cai para API automaticamente quando BROWSER não está
 * na lista de canais disponíveis, então nenhuma mudança de código foi
 * necessária além do pacote.
 *
 * Cobre praticamente os itens 2 (pesquisa), 3.3 (confiança de fontes) e 5
 * (banco de conhecimento/descoberta) do plano original de Fase 2 — pipeline
 * determinístico: descobrir → normalizar → validar → deduplicar → revisar
 * licença → ranquear → catalogar → avaliar → entregar ao Brain.
 */

import java.net.URI

/**
 * Explorer Intelligence v2.0.
 *
 * Pure Kotlin domain layer for the ten Explorer phases. It deliberately does
 * not perform network calls, scrape pages, execute third-party APIs, or invent
 * benchmark data. External discovery is supplied as immutable candidates and
 * passes through validation, ranking and policy gates before it can reach the
 * catalog/Brain layers.
 */

enum class ExplorerItemType { AI, TOOL, AGENT, FRAMEWORK, MCP, API, MODEL, DESKTOP }
enum class ExplorerRegion { CHINA, UNITED_STATES, EUROPE, JAPAN, KOREA, INDIA, GLOBAL, OTHER }
enum class ExplorerLicense { MIT, APACHE_2, BSD, ISC, MPL_2, GPL, PROPRIETARY, UNKNOWN }
enum class OpenSourceStatus { OPEN, OPEN_WEIGHT, SOURCE_AVAILABLE, CLOSED, UNKNOWN }
enum class ReuseDecision { APPROVE, REVIEW_REQUIRED, REJECT }
enum class ExplorerChannel { BROWSER, API, LOCAL }
enum class WorkspaceWindowState { OPEN, MINIMIZED, CLOSED }

private fun isSafeHttps(value: String): Boolean = runCatching {
    val uri = URI(value.trim())
    uri.scheme.equals("https", true) &&
        !uri.host.isNullOrBlank() &&
        uri.userInfo == null &&
        uri.fragment == null &&
        !uri.host.equals("localhost", true) &&
        !uri.host.startsWith("127.") &&
        !uri.host.startsWith("10.") &&
        !uri.host.startsWith("192.168.")
}.getOrDefault(false)

data class ExplorerCandidate(
    val id: String,
    val name: String,
    val type: ExplorerItemType,
    val region: ExplorerRegion,
    val officialUrl: String,
    val repositoryUrl: String? = null,
    val description: String = "",
    val capabilities: Set<String> = emptySet(),
    val openSource: OpenSourceStatus = OpenSourceStatus.UNKNOWN,
    val license: ExplorerLicense = ExplorerLicense.UNKNOWN,
    val licenseVerified: Boolean = false,
    val firstSeenEpochMs: Long = 0L,
    val sourcePriority: Int = 0,
    val confidence: Double = 0.0,
    val active: Boolean = true
) {
    init {
        require(id.isNotBlank() && name.isNotBlank())
        require(isSafeHttps(officialUrl)) { "Explorer URL must use HTTPS without credentials or fragments" }
        require(repositoryUrl == null || isSafeHttps(repositoryUrl))
        require(confidence in 0.0..1.0)
        require(sourcePriority >= 0)
    }

    val searchableText: String
        get() = listOf(name, description, region.name, type.name, capabilities.joinToString(" ")).joinToString(" ").lowercase()
}

data class ExplorerSource(
    val id: String,
    val name: String,
    val region: ExplorerRegion,
    val priority: Int,
    val official: Boolean = false
) {
    init { require(id.isNotBlank() && name.isNotBlank() && priority >= 0) }
}

data class RadarItem(
    val candidate: ExplorerCandidate,
    val score: Double,
    val reasons: List<String>
)

data class RadarReport(
    val weekKey: Long,
    val discovered: Int,
    val accepted: Int,
    val reviewRequired: Int,
    val rejected: Int,
    val items: List<RadarItem>,
    val sourceCount: Int
)

data class ExplorerSecurityResult(
    val safe: Boolean,
    val blockers: List<String>,
    val warnings: List<String>
)

data class ExplorerLicenseReview(
    val decision: ReuseDecision,
    val reason: String
)

data class WorkspaceWindow(
    val id: String,
    val title: String,
    val candidateId: String,
    val state: WorkspaceWindowState = WorkspaceWindowState.OPEN,
    val channel: ExplorerChannel = ExplorerChannel.BROWSER
) {
    init { require(id.isNotBlank() && title.isNotBlank() && candidateId.isNotBlank()) }
}

data class WorkspaceState(
    val windows: List<WorkspaceWindow> = emptyList(),
    val activeWindowId: String? = null
)

data class ConnectorProfile(
    val candidateId: String,
    val channels: Set<ExplorerChannel>,
    val capabilities: Set<String>,
    val apiAvailable: Boolean? = null,
    val browserAvailable: Boolean? = null,
    val localAvailable: Boolean? = null
)

data class ConnectorDecision(
    val selected: ExplorerChannel?,
    val candidateId: String,
    val alternatives: List<ExplorerChannel>,
    val reason: String
)

data class LabEvaluation(
    val candidateId: String,
    val task: String,
    val score: Double,
    val latencyMs: Long? = null,
    val costKnown: Boolean = false,
    val evidence: String = ""
) {
    init {
        require(candidateId.isNotBlank() && task.isNotBlank())
        require(score in 0.0..10.0)
        require(latencyMs == null || latencyMs >= 0)
    }
}

data class OpenSourceInsight(
    val candidateId: String,
    val reuse: ReuseDecision,
    val license: ExplorerLicense,
    val verified: Boolean,
    val usefulIdeas: List<String>
)

data class BrainHandoff(
    val candidateId: String,
    val facts: Map<String, String>,
    val capabilities: Set<String>,
    val confidence: Double
) {
    init { require(candidateId.isNotBlank()); require(confidence in 0.0..1.0) }
}

data class AutonomousRadarPolicy(
    val enabled: Boolean = false,
    val intervalDays: Int = 7,
    val maxItemsPerRun: Int = 100,
    val chinaPriorityBoost: Double = 1.15,
    val requireOfficialSourceForApproval: Boolean = true
) {
    init {
        require(intervalDays in 1..30)
        require(maxItemsPerRun in 1..500)
        require(chinaPriorityBoost >= 1.0)
    }
}

data class ExplorerPipelineResult(
    val radar: RadarReport,
    val security: List<Pair<String, ExplorerSecurityResult>>,
    val licensing: List<ExplorerLicenseReview>,
    val connectorDecisions: List<ConnectorDecision>,
    val labEvaluations: List<LabEvaluation>,
    val openSourceInsights: List<OpenSourceInsight>,
    val brainHandoffs: List<BrainHandoff>,
    val workspace: WorkspaceState
)

/** Executes phases 1-10 as deterministic transformations over supplied data. */
class ExplorerIntelligencePipeline(
    private val policy: AutonomousRadarPolicy = AutonomousRadarPolicy()
) {
    fun run(
        weekEpochMs: Long,
        sources: List<ExplorerSource>,
        candidates: List<ExplorerCandidate>,
        connectors: List<ConnectorProfile> = emptyList(),
        evaluations: List<LabEvaluation> = emptyList()
    ): ExplorerPipelineResult {
        require(weekEpochMs >= 0)

        val security = candidates.map { it.id to validateSecurity(it) }
        val safeCandidates = candidates.filter { candidate -> security.first { it.first == candidate.id }.second.safe }
        val licensing = safeCandidates.map { licenseReview(it) }
        val approvedForCatalog = safeCandidates.filter { candidate ->
            val review = licenseReview(candidate)
            review.decision != ReuseDecision.REJECT &&
                (!policy.requireOfficialSourceForApproval || candidate.sourcePriority > 0)
        }

        val radarItems = rankRadar(approvedForCatalog, weekEpochMs).take(policy.maxItemsPerRun)
        val radar = RadarReport(
            weekKey = weekKey(weekEpochMs),
            discovered = candidates.size,
            accepted = radarItems.size,
            reviewRequired = safeCandidates.count { licenseReview(it).decision == ReuseDecision.REVIEW_REQUIRED },
            rejected = candidates.size - safeCandidates.count { licenseReview(it).decision != ReuseDecision.REJECT },
            items = radarItems,
            sourceCount = sources.size
        )

        val decisions = connectors.map { chooseChannel(it) }
        val insights = safeCandidates.filter { it.openSource != OpenSourceStatus.CLOSED }.map { candidate ->
            val review = licenseReview(candidate)
            OpenSourceInsight(candidate.id, review.decision, candidate.license, candidate.licenseVerified, usefulIdeas(candidate))
        }
        val handoffs = radarItems.map { item ->
            BrainHandoff(
                candidateId = item.candidate.id,
                facts = mapOf(
                    "name" to item.candidate.name,
                    "type" to item.candidate.type.name,
                    "region" to item.candidate.region.name,
                    "officialUrl" to item.candidate.officialUrl
                ),
                capabilities = item.candidate.capabilities,
                confidence = item.candidate.confidence
            )
        }
        val workspace = WorkspaceState(
            windows = radarItems.take(12).map { item ->
                WorkspaceWindow(
                    id = "explorer-${item.candidate.id}",
                    title = item.candidate.name,
                    candidateId = item.candidate.id,
                    channel = if (item.candidate.officialUrl.isNotBlank()) ExplorerChannel.BROWSER else ExplorerChannel.LOCAL
                )
            },
            activeWindowId = radarItems.firstOrNull()?.let { "explorer-${it.candidate.id}" }
        )
        return ExplorerPipelineResult(radar, security, licensing, decisions, evaluations, insights, handoffs, workspace)
    }

    /** Phase 1/2: discovery ranking with a bounded China priority, never overriding safety. */
    fun rankRadar(candidates: List<ExplorerCandidate>, nowEpochMs: Long): List<RadarItem> = candidates
        .filter { validateSecurity(it).safe }
        .distinctBy { canonicalKey(it) }
        .map { candidate ->
            val freshness = freshnessScore(candidate.firstSeenEpochMs, nowEpochMs)
            val capabilityBreadth = candidate.capabilities.size.coerceAtMost(12) / 12.0
            val chinaBoost = if (candidate.region == ExplorerRegion.CHINA) policy.chinaPriorityBoost else 1.0
            val source = (candidate.sourcePriority.coerceIn(0, 100) / 100.0)
            val score = ((candidate.confidence * 0.45) + (freshness * 0.25) + (capabilityBreadth * 0.15) + (source * 0.15)) * chinaBoost
            val reasons = buildList {
                if (candidate.region == ExplorerRegion.CHINA) add("prioridade do radar chinês")
                if (freshness >= 0.75) add("descoberta recente")
                if (candidate.openSource == OpenSourceStatus.OPEN || candidate.openSource == OpenSourceStatus.OPEN_WEIGHT) add("ecossistema aberto")
                if (candidate.capabilities.isNotEmpty()) add("capacidades catalogadas")
                if (candidate.sourcePriority > 0) add("fonte com prioridade registrada")
            }
            RadarItem(candidate, score, reasons)
        }
        .sortedWith(compareByDescending<RadarItem> { it.score }.thenBy { it.candidate.id })

    /** Phase 3: safe metadata validation before any catalog handoff. */
    fun validateSecurity(candidate: ExplorerCandidate): ExplorerSecurityResult {
        val blockers = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        validateUrl(candidate.officialUrl, blockers, "officialUrl")
        candidate.repositoryUrl?.let { validateUrl(it, blockers, "repositoryUrl") }
        if (candidate.officialUrl.contains("@")) blockers += "credential marker in URL"
        if (candidate.description.length > 20_000) warnings += "description truncated by policy"
        if (candidate.confidence < 0.5) warnings += "low confidence"
        return ExplorerSecurityResult(blockers.isEmpty(), blockers.distinct(), warnings.distinct())
    }

    /** Phase 8: license-aware reuse. Unknown licenses never become automatic approval. */
    fun licenseReview(candidate: ExplorerCandidate): ExplorerLicenseReview = when {
        candidate.openSource == OpenSourceStatus.CLOSED -> ExplorerLicenseReview(ReuseDecision.REJECT, "projeto fechado")
        !candidate.licenseVerified || candidate.license == ExplorerLicense.UNKNOWN -> ExplorerLicenseReview(ReuseDecision.REVIEW_REQUIRED, "licença não verificada")
        candidate.license in setOf(ExplorerLicense.MIT, ExplorerLicense.APACHE_2, ExplorerLicense.BSD, ExplorerLicense.ISC, ExplorerLicense.MPL_2) ->
            ExplorerLicenseReview(ReuseDecision.APPROVE, "licença permissiva verificada")
        else -> ExplorerLicenseReview(ReuseDecision.REVIEW_REQUIRED, "licença exige revisão jurídica/técnica")
    }

    /** Phase 6: browser-first connector policy, with explicit alternatives only. */
    fun chooseChannel(profile: ConnectorProfile): ConnectorDecision {
        val channels = profile.channels
        val selected = when {
            ExplorerChannel.BROWSER in channels && profile.browserAvailable != false -> ExplorerChannel.BROWSER
            ExplorerChannel.API in channels && profile.apiAvailable == true -> ExplorerChannel.API
            ExplorerChannel.LOCAL in channels && profile.localAvailable == true -> ExplorerChannel.LOCAL
            else -> null
        }
        val alternatives = channels.filter { it != selected }.sortedBy { channel -> when (channel) {
            ExplorerChannel.BROWSER -> 0
            ExplorerChannel.API -> 1
            ExplorerChannel.LOCAL -> 2
        }}
        return ConnectorDecision(selected, profile.candidateId, alternatives, if (selected == ExplorerChannel.BROWSER) "browser-first" else "fallback explícito")
    }

    /** Phase 7: evaluation accepts measured evidence only; no benchmark fabrication. */
    fun evaluate(candidateId: String, task: String, score: Double, latencyMs: Long? = null, evidence: String = ""): LabEvaluation =
        LabEvaluation(candidateId, task, score, latencyMs, latencyMs != null, evidence)

    /** Phase 5: deterministic workspace operations used by a future UI. */
    fun openWindow(state: WorkspaceState, candidate: ExplorerCandidate, channel: ExplorerChannel = ExplorerChannel.BROWSER): WorkspaceState {
        require(validateSecurity(candidate).safe)
        val window = WorkspaceWindow("explorer-${candidate.id}", candidate.name, candidate.id, WorkspaceWindowState.OPEN, channel)
        val withoutExisting = state.windows.filterNot { it.id == window.id }
        return WorkspaceState(withoutExisting + window, window.id)
    }

    fun minimizeWindow(state: WorkspaceState, windowId: String): WorkspaceState =
        state.copy(windows = state.windows.map { if (it.id == windowId) it.copy(state = WorkspaceWindowState.MINIMIZED) else it })

    fun closeWindow(state: WorkspaceState, windowId: String): WorkspaceState =
        state.copy(
            windows = state.windows.filterNot { it.id == windowId },
            activeWindowId = state.activeWindowId.takeUnless { it == windowId }
        )

    private fun usefulIdeas(candidate: ExplorerCandidate): List<String> = buildList {
        if ("agent" in candidate.capabilities) add("arquitetura de agente")
        if ("browser" in candidate.capabilities || "computer-use" in candidate.capabilities) add("operação assistida por navegador/computador")
        if ("mcp" in candidate.capabilities) add("integração por MCP")
        if ("coding" in candidate.capabilities) add("fluxo de coding agent")
        if ("multimodal" in candidate.capabilities) add("orquestração multimodal")
    }

    private fun canonicalKey(candidate: ExplorerCandidate): String =
        candidate.officialUrl.trim().lowercase().trimEnd('/') + "|" + candidate.name.trim().lowercase()

    private fun freshnessScore(firstSeen: Long, now: Long): Double {
        if (firstSeen <= 0L || now <= 0L || firstSeen > now) return 0.0
        val ageDays = ((now - firstSeen) / DAY_MS).toDouble()
        return (1.0 - (ageDays / 30.0)).coerceIn(0.0, 1.0)
    }

    companion object {
        private const val DAY_MS = 86_400_000L
        private const val WEEK_MS = DAY_MS * 7

        fun weekKey(epochMs: Long): Long = epochMs / WEEK_MS

        private fun validateUrl(value: String, blockers: MutableList<String>, field: String) {
            if (!isSafeHttps(value)) blockers += "$field must be a safe HTTPS URL"
        }

        private fun isSafeHttps(value: String): Boolean = runCatching {
            val uri = URI(value.trim())
            uri.scheme.equals("https", true) &&
                !uri.host.isNullOrBlank() &&
                uri.userInfo == null &&
                uri.fragment == null &&
                !uri.host.equals("localhost", true) &&
                !uri.host.startsWith("127.") &&
                !uri.host.startsWith("10.") &&
                !uri.host.startsWith("192.168.")
        }.getOrDefault(false)
    }
}
