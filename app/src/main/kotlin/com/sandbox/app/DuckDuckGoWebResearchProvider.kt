package com.sandbox.app

import com.brain.research.QuerySanitizer
import com.brain.research.ResearchResult
import com.brain.research.WebResearchProvider
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant

/**
 * Pesquisa real na web sem exigir chave de API — usa o endpoint HTML do
 * DuckDuckGo (mesma técnica usada por várias ferramentas de linha de comando
 * sem servidor próprio). Falha de forma explícita quando não há rede,
 * quando o parsing não encontra nada, ou em timeout — nunca inventa fontes.
 */
class DuckDuckGoWebResearchProvider(
    private val timeoutMs: Int = 12_000
) : WebResearchProvider {

    override fun pesquisar(query: String, maxResultados: Int): Result<List<ResearchResult>> = runCatching {
        val sanitizada = QuerySanitizer.sanitizar(query)
        require(sanitizada.isNotBlank()) { "consulta vazia após sanitização" }
        val encoded = URLEncoder.encode(sanitizada, "UTF-8")
        val connection = (URL("https://html.duckduckgo.com/html/?q=$encoded").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            setRequestProperty("User-Agent", "Mozilla/5.0 (Android) BrainCode-WebResearch/1.0")
        }
        val body = try {
            val status = connection.responseCode
            if (status !in 200..299) error("HTTP $status ao consultar o provedor de pesquisa")
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
        val retrievedAt = Instant.now()
        parseResultados(body, sanitizada, retrievedAt).take(maxResultados)
    }

    /** Extração leve por regex — evita depender de um parser HTML completo (custo de RAM/APK em Android). */
    private fun parseResultados(html: String, query: String, retrievedAt: Instant): List<ResearchResult> {
        val bloco = Regex(
            "<a[^>]*class=\"result__a\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>.*?<a[^>]*class=\"result__snippet\"[^>]*>(.*?)</a>",
            setOf(RegexOption.DOT_MATCHES_ALL)
        )
        return bloco.findAll(html).mapNotNull { m ->
            val url = decodeDuckDuckGoUrl(m.groupValues[1])
            val title = stripHtml(m.groupValues[2])
            val snippet = stripHtml(m.groupValues[3])
            if (url.isBlank() || title.isBlank()) null
            else ResearchResult(
                query = query,
                source = hostOf(url),
                title = title,
                url = url,
                relevantContent = snippet,
                retrievedAt = retrievedAt
            )
        }.toList()
    }

    private fun decodeDuckDuckGoUrl(raw: String): String {
        // DuckDuckGo HTML redireciona via /l/?uddg=<url-encoded>; extrai o destino real quando presente.
        val marker = "uddg="
        val idx = raw.indexOf(marker)
        val candidate = if (idx >= 0) raw.substring(idx + marker.length).substringBefore('&') else raw
        return runCatching { java.net.URLDecoder.decode(candidate, "UTF-8") }.getOrDefault(candidate)
    }

    private fun stripHtml(fragment: String): String = HtmlTextDecoder.decode(fragment)

    private fun hostOf(url: String): String = runCatching { URL(url).host }.getOrDefault("web")
}
