package com.sandbox.app

import com.brain.research.FetchProvider
import com.brain.research.ResearchRequest
import com.brain.research.ResearchResult
import java.net.URL
import java.time.Instant
import org.json.JSONObject

/** Fetch opcional via navegador remoto (25 req/dia no endpoint free); fica atrás do fetch HTTP direto. */
class MicrolinkFetchProvider(
    private val http: ApiHttpClient = UrlConnectionApiHttpClient(timeoutMs = 15_000)
) : FetchProvider {
    override fun fetch(url: String, request: ResearchRequest): Result<ResearchResult> = runCatching {
        val target = URL(url)
        require(target.protocol == "https" && !target.host.isNullOrBlank()) { "Microlink só aceita URL HTTPS válida" }
        val endpoint = "https://api.microlink.io/?url=${url.encodeQuery()}&data.markdown.attr=markdown&meta=false"
        val response = JSONObject(http.get(endpoint, mapOf("Accept" to "application/json")).requireSuccess("Microlink"))
        if (response.optString("status") != "success") error("Microlink não conseguiu extrair a página: ${response.optString("status")}")
        val data = response.optJSONObject("data") ?: error("resposta Microlink inválida: data ausente")
        val markdown = data.optString("markdown").trim()
        if (markdown.length < 40) error("Microlink retornou conteúdo textual insuficiente")
        val returnedUrl = data.optString("url").takeIf { it.startsWith("https://") } ?: url
        ResearchResult(request.query, URL(returnedUrl).host, data.optString("title").ifBlank { target.host }, returnedUrl, markdown.take(request.constraints.maxContentCharsPerSource), Instant.now())
    }
}
