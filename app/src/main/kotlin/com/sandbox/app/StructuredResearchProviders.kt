package com.sandbox.app

import com.brain.research.QuerySanitizer
import com.brain.research.ResearchRequest
import com.brain.research.ResearchResult
import com.brain.research.SearchProvider
import com.brain.research.ResearchIntentClassifier
import com.brain.research.ResearchIntentKind
import java.net.URL
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject

const val BRAVE_SEARCH_KEY_ID = "brave-search"

/** Busca Brave v1. A credencial é lida da store cifrada em cada chamada e nunca entra na URL/logs. */
class BraveSearchWebResearchProvider(
    private val apiKey: () -> String?,
    private val http: ApiHttpClient = UrlConnectionApiHttpClient()
) : SearchProvider {
    override fun search(request: ResearchRequest): Result<List<ResearchResult>> = runCatching {
        val key = apiKey()?.trim()?.takeIf { it.isNotEmpty() } ?: error("Brave Search sem chave configurada")
        val query = QuerySanitizer.sanitizar(request.query).takeIf { it.isNotBlank() } ?: error("consulta vazia após sanitização")
        val url = "https://api.search.brave.com/res/v1/web/search?q=${query.encodeQuery()}&count=${request.constraints.maxSources.coerceIn(1, 20)}"
        val response = http.get(url, mapOf("X-Subscription-Token" to key))
        when (response.statusCode) {
            401 -> error("Brave Search rejeitou a chave (HTTP 401)")
            403 -> error("Brave Search negou acesso à chave/plano (HTTP 403)")
            429 -> error("Brave Search limitou a consulta (HTTP 429)")
        }
        val body = response.requireSuccess("Brave Search")
        val results = JSONObject(body).optJSONObject("web")?.optJSONArray("results")
            ?: error("resposta Brave inválida: campo web.results ausente")
        val mapped = (0 until results.length()).mapNotNull { index ->
            val item = results.optJSONObject(index) ?: return@mapNotNull null
            val title = item.optString("title").trim()
            val urlValue = item.optString("url").trim()
            val host = runCatching { URL(urlValue).takeIf { it.protocol == "https" }?.host }.getOrNull()
            val description = sequenceOf(item.optString("description"))
                .plus((0 until (item.optJSONArray("extra_snippets")?.length() ?: 0)).map { item.optJSONArray("extra_snippets")!!.optString(it) })
                .map { it.trim() }.filter { it.isNotBlank() }.distinct().joinToString(" ")
            if (title.isBlank() || host.isNullOrBlank() || description.isBlank()) return@mapNotNull null
            ResearchResult(query, host, title, urlValue, description, Instant.now(), confidence = .72)
        }.take(request.constraints.maxSources)
        if (mapped.isEmpty()) error("Brave Search retornou 0 resultados utilizáveis")
        mapped
    }
}

/** Busca labels/aliases no Wikidata e acrescenta claims factuais reais quando a pergunta pede data/população/capital. */
class WikidataSearchProvider(
    private val http: ApiHttpClient = UrlConnectionApiHttpClient(),
    private val language: String = "pt"
) : SearchProvider {
    override fun search(request: ResearchRequest): Result<List<ResearchResult>> = runCatching {
        val query = SearchQueryTerms.subject(QuerySanitizer.sanitizar(request.query))
        if (query.isBlank()) error("consulta Wikidata vazia após sanitização")
        val url = "https://www.wikidata.org/w/api.php?action=wbsearchentities&search=${query.encodeQuery()}&language=${language.encodeQuery()}&uselang=${language.encodeQuery()}&format=json&limit=${request.constraints.maxSources.coerceIn(1, 10)}&props=url"
        val response = http.get(url, mapOf("Accept" to "application/json", "Api-User-Agent" to "BrainCode/1.0 (Android app; public factual lookup)"))
        val hits = JSONObject(response.requireSuccess("Wikidata")).optJSONArray("search") ?: error("resposta Wikidata inválida: search ausente")
        if (hits.length() == 0) error("Wikidata retornou 0 resultados")
        val results = (0 until hits.length()).mapNotNull { index ->
            val item = hits.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("id").trim()
            val label = item.optString("label").trim()
            if (!id.matches(Regex("Q[0-9]+")) || label.isBlank()) return@mapNotNull null
            val description = item.optString("description").trim()
            val facts = readFacts(id, request.query)
            val text = buildList {
                add("Identificador Wikidata: $id.")
                if (description.isNotBlank()) add(description)
                facts.takeIf { it.isNotEmpty() }?.let { add("Dados estruturados: ${it.joinToString("; ")}.") }
            }.joinToString(" ")
            ResearchResult(request.query, "www.wikidata.org", label, "https://www.wikidata.org/wiki/$id", text, Instant.now(), confidence = if (facts.isNotEmpty()) .86 else .62)
        }.take(request.constraints.maxSources)
        if (results.isEmpty()) error("Wikidata não retornou entidade rastreável")
        results
    }

    private fun readFacts(entityId: String, query: String): List<String> {
        val properties = when {
            Regex("(?i)\b(nasceu|nascimento|born|birth)\b").containsMatchIn(query) -> listOf("P569")
            Regex("(?i)\b(morreu|morte|falecimento|died|death)\b").containsMatchIn(query) -> listOf("P570")
            Regex("(?i)\b(popula|habitantes|population)\b").containsMatchIn(query) -> listOf("P1082")
            Regex("(?i)\b(capital)\b").containsMatchIn(query) -> listOf("P36")
            Regex("(?i)\b(fundad|fundação|founded)\b").containsMatchIn(query) -> listOf("P571")
            else -> emptyList()
        }
        if (properties.isEmpty()) return emptyList()
        val url = "https://www.wikidata.org/w/api.php?action=wbgetentities&ids=$entityId&props=claims&format=json"
        val response = http.get(url, mapOf("Accept" to "application/json", "Api-User-Agent" to "BrainCode/1.0 (Android app; public factual lookup)"))
        val entity = JSONObject(response.requireSuccess("Wikidata claims")).optJSONObject("entities")?.optJSONObject(entityId) ?: return emptyList()
        val claims = entity.optJSONObject("claims") ?: return emptyList()
        return properties.flatMap { property ->
            val statements = claims.optJSONArray(property) ?: return@flatMap emptyList()
            (0 until minOf(statements.length(), 3)).mapNotNull { i ->
                val value = statements.optJSONObject(i)?.optJSONObject("mainsnak")?.optJSONObject("datavalue")?.opt("value")
                val rendered = when (value) {
                    is JSONObject -> value.optString("time").takeIf { it.isNotBlank() }?.removePrefix("+")?.substringBefore("T")
                        ?: value.optString("amount").takeIf { it.isNotBlank() }
                        ?: value.optJSONObject("numeric-id")?.toString()
                    is Number, is String -> value.toString()
                    else -> null
                }
                rendered?.let { "$property=$it" }
            }
        }
    }
}

/** Pesquisa questões técnicas; respeita erros/backoff e preserva links originais. */
class StackExchangeSearchProvider(
    private val http: ApiHttpClient = UrlConnectionApiHttpClient(),
    private val site: String = "stackoverflow"
) : SearchProvider {
    override fun search(request: ResearchRequest): Result<List<ResearchResult>> = runCatching {
        val query = QuerySanitizer.sanitizar(request.query).takeIf { it.isNotBlank() } ?: error("consulta Stack Exchange vazia")
        val url = "https://api.stackexchange.com/2.3/search/advanced?order=desc&sort=relevance&site=${site.encodeQuery()}&q=${query.encodeQuery()}&pagesize=${request.constraints.maxSources.coerceIn(1, 5)}&filter=withbody"
        val root = JSONObject(http.get(url, mapOf("Accept" to "application/json")).requireSuccess("Stack Exchange"))
        if (root.has("error_id")) error("Stack Exchange ${root.optString("error_name")}: ${root.optString("error_message")}")
        val items = root.optJSONArray("items") ?: error("resposta Stack Exchange inválida: items ausente")
        if (items.length() == 0) error("Stack Exchange retornou 0 resultados")
        (0 until items.length()).mapNotNull { index ->
            val item = items.optJSONObject(index) ?: return@mapNotNull null
            val title = item.optString("title").trim()
            val link = item.optString("link").trim()
            val host = runCatching { URL(link).takeIf { it.protocol == "https" }?.host }.getOrNull()
            val tags = item.optJSONArray("tags")?.let { tags -> (0 until tags.length()).joinToString(", ") { tags.optString(it) } }.orEmpty()
            val body = item.optString("body_markdown").ifBlank { HtmlTextDecoder.decode(item.optString("body")) }.trim()
            val score = item.optInt("score", 0)
            val excerpt = listOfNotNull(body.takeIf { it.isNotBlank() }?.take(900), tags.takeIf { it.isNotBlank() }?.let { "Tags: $it" }, "Pontuação informada pela API: $score").joinToString(" ")
            if (title.isBlank() || host.isNullOrBlank()) return@mapNotNull null
            ResearchResult(request.query, host, title, link, excerpt, Instant.now(), confidence = .7)
        }.also { if (it.isEmpty()) error("Stack Exchange não retornou perguntas rastreáveis") }
    }
}

/** Busca de trabalhos acadêmicos com reconstrução fiel do abstract invertido do OpenAlex. */
class OpenAlexSearchProvider(
    private val http: ApiHttpClient = UrlConnectionApiHttpClient()
) : SearchProvider {
    override fun search(request: ResearchRequest): Result<List<ResearchResult>> = runCatching {
        val query = SearchQueryTerms.subject(QuerySanitizer.sanitizar(request.query))
        if (query.isBlank()) error("consulta OpenAlex vazia")
        val fields = "id,doi,display_name,publication_year,authorships,abstract_inverted_index"
        val url = "https://api.openalex.org/works?search=${query.encodeQuery()}&per-page=${request.constraints.maxSources.coerceIn(1, 10)}&select=$fields"
        val root = JSONObject(http.get(url, mapOf("Accept" to "application/json")).requireSuccess("OpenAlex"))
        val items = root.optJSONArray("results") ?: error("resposta OpenAlex inválida: results ausente")
        if (items.length() == 0) error("OpenAlex retornou 0 trabalhos")
        val results = (0 until items.length()).mapNotNull { index ->
            val item = items.optJSONObject(index) ?: return@mapNotNull null
            val title = item.optString("display_name").trim()
            val id = item.optString("id").trim()
            val doi = item.optString("doi").trim()
            val urlValue = doi.takeIf { it.startsWith("https://doi.org/") } ?: id.takeIf { it.startsWith("https://openalex.org/") }
            if (title.isBlank() || urlValue.isNullOrBlank()) return@mapNotNull null
            val authors = item.optJSONArray("authorships")?.let { array ->
                (0 until minOf(array.length(), 8)).mapNotNull { i -> array.optJSONObject(i)?.optJSONObject("author")?.optString("display_name")?.takeIf { it.isNotBlank() } }.joinToString(", ")
            }.orEmpty()
            val abstract = reconstructAbstract(item.optJSONObject("abstract_inverted_index"))
            val content = buildList {
                item.optInt("publication_year").takeIf { it > 0 }?.let { add("Ano: $it.") }
                authors.takeIf { it.isNotBlank() }?.let { add("Autores: $it.") }
                doi.takeIf { it.startsWith("https://") }?.let { add("DOI: $it.") }
                abstract.takeIf { it.isNotBlank() }?.let { add(it) }
            }.joinToString(" ").ifBlank { title }
            ResearchResult(request.query, "openalex.org", title, urlValue, content, Instant.now(), confidence = if (abstract.isNotBlank()) .82 else .68)
        }.take(request.constraints.maxSources)
        if (results.isEmpty()) error("OpenAlex não retornou trabalhos com identificador e título")
        results
    }

    private fun reconstructAbstract(index: JSONObject?): String {
        if (index == null) return ""
        val words = sortedMapOf<Int, String>()
        val keys = index.keys()
        while (keys.hasNext()) {
            val word = keys.next()
            val positions = index.optJSONArray(word) ?: continue
            for (i in 0 until positions.length()) {
                val position = positions.optInt(i, -1)
                if (position >= 0) words.putIfAbsent(position, word)
            }
        }
        return words.values.joinToString(" ").take(5_000)
    }
}

/** Selection is deterministic, sequential and failover-only: one category provider, Brave, then DDG. */
class ResearchSearchRoutingProvider(
    private val brave: SearchProvider,
    private val duckDuckGo: SearchProvider,
    private val wikidata: SearchProvider,
    private val stackExchange: SearchProvider,
    private val openAlex: SearchProvider
) : SearchProvider {
    override fun search(request: ResearchRequest): Result<List<ResearchResult>> {
        val first = when (ResearchIntentClassifier.classify(request.query)) {
            ResearchIntentKind.FACTUAL -> wikidata
            ResearchIntentKind.TECHNICAL -> stackExchange
            ResearchIntentKind.ACADEMIC -> openAlex
            ResearchIntentKind.GENERAL -> brave
        }
        val ordered = if (first === brave) listOf(brave, duckDuckGo) else listOf(first, brave, duckDuckGo)
        var lastFailure: Throwable? = null
        for (provider in ordered) {
            val outcome = runCatching { provider.search(request) }.getOrElse { Result.failure(it) }
            val results = outcome.getOrNull().orEmpty()
            if (results.isNotEmpty()) return Result.success(results.take(request.constraints.maxSources))
            lastFailure = outcome.exceptionOrNull() ?: IllegalStateException("provider retornou 0 resultados")
        }
        return Result.failure(IllegalStateException("todos os providers de pesquisa falharam", lastFailure))
    }

}

internal object SearchQueryTerms {
    fun subject(query: String): String = query
        .replace(Regex("(?i)^\\s*(quando nasceu|onde nasceu|qual a data de nascimento de|data de nascimento de|quem foi|quem é|quem e|paper(s)? sobre|artigo(s)? científico(s)? sobre|estudo(s)? acadêmico(s)? sobre)\\s+"), "")
        .replace(Regex("(?i)\\b(hoje|agora|por favor|na internet|na web)\\b"), " ")
        .replace(Regex("\\s+"), " ").trim().trimEnd('?', '.', '!')
}
