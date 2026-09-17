package com.sandbox.app

import com.brain.research.QuerySanitizer
import com.brain.research.ResearchResult
import com.brain.research.WebResearchProvider
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant

/**
 * Fallback do WebResearch: API pública de busca da Wikipedia (JSON estruturado,
 * sem chave). Estruturalmente independente do DuckDuckGoWebResearchProvider —
 * não depende de parsing de HTML, então uma mudança de marcação numa fonte
 * não derruba as duas ao mesmo tempo. Cobertura menor (só o que está na
 * Wikipedia), por isso fica em segundo lugar na cadeia, nunca em primeiro.
 */
class WikipediaWebResearchProvider(
    private val idioma: String = "pt",
    private val timeoutMs: Int = 12_000
) : WebResearchProvider {

    override fun pesquisar(query: String, maxResultados: Int): Result<List<ResearchResult>> = runCatching {
        val sanitizada = QuerySanitizer.sanitizar(query)
        require(sanitizada.isNotBlank()) { "consulta vazia após sanitização" }
        val encoded = URLEncoder.encode(sanitizada, "UTF-8")
        val url = "https://$idioma.wikipedia.org/w/api.php" +
            "?action=query&list=search&format=json&srlimit=$maxResultados&srsearch=$encoded"
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            setRequestProperty("User-Agent", "Mozilla/5.0 (Android) BrainCode-WebResearch/1.0")
        }
        val body = try {
            val status = connection.responseCode
            if (status !in 200..299) error("HTTP $status ao consultar a Wikipedia")
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
        parseResultados(body, sanitizada).take(maxResultados)
    }

    private fun parseResultados(body: String, query: String): List<ResearchResult> {
        val retrievedAt = Instant.now()
        val hits = JSONObject(body).optJSONObject("query")?.optJSONArray("search") ?: return emptyList()
        return (0 until hits.length()).mapNotNull { i ->
            val hit = hits.optJSONObject(i) ?: return@mapNotNull null
            val title = hit.optString("title").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val snippet = stripHtml(hit.optString("snippet"))
            val urlDoArtigo = "https://$idioma.wikipedia.org/wiki/${URLEncoder.encode(title.replace(' ', '_'), "UTF-8")}"
            ResearchResult(
                query = query,
                source = "$idioma.wikipedia.org",
                title = title,
                url = urlDoArtigo,
                relevantContent = snippet,
                retrievedAt = retrievedAt
            )
        }
    }

    private fun stripHtml(fragment: String): String =
        fragment.replace(Regex("<[^>]+>"), "").replace("&amp;", "&").replace("&quot;", "\"").trim()
}
