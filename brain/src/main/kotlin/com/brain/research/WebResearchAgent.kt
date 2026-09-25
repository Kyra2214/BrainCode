package com.brain.research

import java.net.URI
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Harness de pesquisa do BrainCode. SearchClaw e Firecrawl são referências de
 * arquitetura, não runtimes obrigatórios: o agente trabalha com providers
 * injetados e funciona sem LLM quando o provider determinístico é suficiente.
 */
class WebResearchAgent(
    private val providers: WebProviderSet,
    private val clock: Clock = Clock.systemUTC(),
    private val runId: () -> String = { "research-${UUID.randomUUID()}" }
) {
    fun research(request: ResearchRequest): ResearchRunResult {
        val started = clock.instant()
        val safeQuery = QuerySanitizer.sanitizar(ResearchQueryRewriter.rewrite(request.query))
        if (safeQuery.isBlank()) return failure(request, started, "consulta removida pela política de segurança")
        if (request.networkPolicy == NetworkPolicy.OFFLINE_ONLY) {
            return failure(request, started, "pesquisa online desativada pela política; use o cache local")
        }
        val failures = mutableListOf<FailedSource>()
        val found = mutableListOf<ResearchResult>()
        var providerId = "none"
        for ((index, provider) in providers.search.withIndex()) {
            if (found.size >= request.constraints.maxSources) break
            val outcome = runCatching {
                provider.search(request.copy(query = safeQuery))
            }.getOrElse { Result.failure(it) }
            outcome.onSuccess { results ->
                val accepted = results.filter { isAllowed(it, request.constraints) }
                if (accepted.isNotEmpty()) {
                    providerId = "search-$index"
                    found += accepted.take(request.constraints.maxSources - found.size)
                        .map { enrichWithRealContent(it, request) }
                }
            }.onFailure { error ->
                failures += FailedSource("search-$index", safeQuery, error.message ?: "falha de pesquisa")
            }
            // Cada categoria e fallback é consultado apenas se a etapa anterior
            // falhou, retornou vazio ou não produziu URL/conteúdo aceitável.
            if (found.isNotEmpty()) break
        }
        if (found.isEmpty()) return failure(request, started, failures.lastOrNull()?.diagnostic ?: "nenhum provider retornou fonte", failures)
        val distinctDomains = found.mapNotNull { domain(it.url) }.toSet().size
        val quality = quality(found, request.sourceRequirements, distinctDomains)
        val citations = found.mapIndexed { index, item ->
            ResearchCitation(index + 1, item.title, item.url, item.source, item.relevantContent.take(280))
        }
        val evidence = found.mapIndexed { index, item ->
            ResearchEvidence("$index:${item.url}", "source", item.relevantContent.take(request.constraints.maxContentCharsPerSource), item.url, item.retrievedAt)
        }
        val finished = clock.instant()
        return ResearchRunResult(
            answer = ResearchAnswerSynthesizer.synthesize(request.query, found),
            sources = found,
            evidence = evidence,
            citations = citations,
            sourceQuality = quality,
            confidence = (found.map { it.confidence }.average().coerceIn(0.0, 1.0)),
            failedSources = failures,
            execution = ResearchExecutionMetadata(runId(), started, finished, steps = 1, providerIds = listOf(providerId), networkUsed = true, requestId = request.requestId, conversationId = request.conversationId),
            diagnostic = failures.takeIf { it.isNotEmpty() }?.joinToString("; ") { it.diagnostic },
            userMessage = if (quality == SourceQuality.REJECTED) "Encontrei fontes insuficientes para uma resposta confiável." else null
        )
    }

    private fun failure(request: ResearchRequest, started: Instant, diagnostic: String, failures: List<FailedSource> = emptyList()): ResearchRunResult {
        val finished = clock.instant()
        return ResearchRunResult(
            answer = "",
            failedSources = failures,
            execution = ResearchExecutionMetadata(runId(), started, finished, 0, emptyList(), networkUsed = false, requestId = request.requestId, conversationId = request.conversationId),
            diagnostic = diagnostic,
            userMessage = "Não consegui concluir a pesquisa agora. Posso tentar novamente ou responder apenas com conhecimento local."
        )
    }

    private fun quality(results: List<ResearchResult>, requirements: SourceRequirements, domains: Int): SourceQuality {
        if (results.isEmpty() || domains < requirements.minimumDistinctDomains) return SourceQuality.REJECTED
        val average = results.map { it.confidence }.average()
        return when {
            average >= .8 && domains >= requirements.minimumDistinctDomains -> SourceQuality.HIGH
            average >= .5 -> SourceQuality.MEDIUM
            else -> SourceQuality.LOW
        }
    }

    /**
     * Substitui o teaser da SERP pelo conteúdo real da página quando algum
     * `FetchProvider` estiver configurado (ver `WebProviderSet.fetch`) — esse
     * seam já existia no contrato, mas nenhum caller o usava, então
     * `relevantContent` nunca era mais que o snippet do buscador.
     *
     * Só recalcula `confidence` quando o provider de busca não assumiu esse
     * papel (deixou no default 0.0): um provider que já calcula seu próprio
     * confidence continua no controle e não é sobrescrito aqui.
     */
    private fun enrichWithRealContent(result: ResearchResult, request: ResearchRequest): ResearchResult {
        val paginaReal = fetchRealContent(result.url, request)
        val conteudoFinal = paginaReal?.takeIf { it.isNotBlank() } ?: result.relevantContent
        val truncado = conteudoFinal.take(request.constraints.maxContentCharsPerSource)
        return if (result.confidence == 0.0) {
            result.copy(relevantContent = truncado, confidence = ContentRelevanceScorer.score(truncado, request.query))
        } else {
            result.copy(relevantContent = truncado)
        }
    }

    private fun fetchRealContent(url: String, request: ResearchRequest): String? {
        for (fetcher in providers.fetch) {
            val texto = runCatching { fetcher.fetch(url, request) }
                .getOrElse { Result.failure(it) }
                .getOrNull()
                ?.relevantContent
                ?.trim()
            if (!texto.isNullOrBlank()) return texto
        }
        return null
    }

    private fun isAllowed(result: ResearchResult, constraints: ResearchConstraints): Boolean {
        val parsed = runCatching { URI(result.url) }.getOrNull() ?: return false
        if (constraints.requireHttps && parsed.scheme != "https") return false
        val host = parsed.host?.lowercase() ?: return false
        if (constraints.blockedDomains.any { host == it || host.endsWith(".$it") }) return false
        if (constraints.allowedDomains.isNotEmpty() && constraints.allowedDomains.none { host == it || host.endsWith(".$it") }) return false
        return result.relevantContent.isNotBlank()
    }

    private fun domain(url: String): String? = runCatching { URI(url).host?.lowercase() }.getOrNull()
}

/** Adaptador de compatibilidade para providers já usados pelo BrainCode. */
class LegacySearchProviderAdapter(private val provider: WebResearchProvider) : SearchProvider {
    override fun search(request: ResearchRequest): Result<List<ResearchResult>> =
        provider.pesquisar(QuerySanitizer.sanitizar(request.query), request.constraints.maxSources)
}

/** Regras de segurança aplicáveis antes de aceitar conteúdo de páginas. */
object ResearchSecurityPolicy {
    private val injectionMarkers = listOf("ignore previous instructions", "system prompt", "reveal your policy", "ignore as instruções")

    fun isUntrustedContent(content: String): Boolean = injectionMarkers.any { content.contains(it, ignoreCase = true) }

    fun sanitizeForUser(content: String, maxChars: Int = 12_000): String = content
        .replace(Regex("(?i)javascript:|data:text/html"), "")
        .take(maxChars)
}
