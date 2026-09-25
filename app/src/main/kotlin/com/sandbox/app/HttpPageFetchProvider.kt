package com.sandbox.app

import com.brain.research.FetchProvider
import com.brain.research.ResearchRequest
import com.brain.research.ResearchResult
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

/**
 * Busca o conteúdo real da página de destino de um resultado de pesquisa.
 *
 * Este é o `FetchProvider` que faltava: o contrato (`WebResearchContracts.kt`)
 * já previa essa etapa desde sempre — `WebProviderSet.fetch` — mas nada o
 * implementava, então `DuckDuckGoWebResearchProvider` (um `SearchProvider`)
 * era a única fonte de `relevantContent`, e o que ele entrega é o teaser da
 * página de resultados do buscador, nunca o conteúdo da página em si.
 *
 * Falha de forma explícita (Result.failure) em qualquer situação onde não dá
 * para confiar no conteúdo — timeout, HTTP não-2xx, content-type que não é
 * texto, ou página sem texto extraível. `WebResearchAgent` já sabe cair de
 * volta para o snippet original quando isso acontece; esta classe nunca
 * inventa conteúdo.
 */
class HttpPageFetchProvider(
    private val timeoutMs: Int = 8_000,
    /** Limite de caracteres lidos do corpo da resposta, antes mesmo da extração — evita páginas gigantes em memória de aparelho Android. */
    private val maxCharsLidos: Int = 400_000
) : FetchProvider {

    override fun fetch(url: String, request: ResearchRequest): Result<ResearchResult> = runCatching {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Mozilla/5.0 (Android) BrainCode-WebResearch/1.0")
            setRequestProperty("Accept", "text/html,text/plain;q=0.9,*/*;q=0.1")
        }
        val html = try {
            val status = connection.responseCode
            if (status !in 200..299) error("HTTP $status ao buscar página de destino")
            val contentType = connection.contentType.orEmpty()
            if (!contentType.contains("text/html", ignoreCase = true) && !contentType.contains("text/plain", ignoreCase = true)) {
                error("content-type não suportado para extração de texto: $contentType")
            }
            connection.inputStream.bufferedReader().use { it.readTextLimitado(maxCharsLidos) }
        } finally {
            connection.disconnect()
        }
        val texto = ReadablePageTextExtractor.extract(html)
        if (texto.length < 40) error("página sem conteúdo textual utilizável (${texto.length} caracteres após extração)")
        ResearchResult(
            query = request.query,
            source = runCatching { URL(url).host }.getOrDefault("web"),
            title = "",
            url = url,
            relevantContent = texto,
            retrievedAt = Instant.now()
        )
    }

    private fun BufferedReader.readTextLimitado(maxChars: Int): String {
        val buffer = CharArray(8_192)
        val sb = StringBuilder()
        while (sb.length < maxChars) {
            val lidos = read(buffer)
            if (lidos == -1) break
            sb.append(buffer, 0, lidos)
        }
        return sb.toString()
    }
}

/**
 * Extrai texto legível de um HTML bruto: remove `<script>`/`<style>`/`<noscript>`
 * inteiros (não só as tags — o *conteúdo* deles não é texto de página),
 * depois as demais tags, decodifica entidades e colapsa espaços em branco.
 *
 * Extração leve por regex, na mesma linha de `DuckDuckGoWebResearchProvider`:
 * evita depender de um parser HTML completo (custo de RAM/APK em Android).
 * Não é um extrator de "conteúdo principal" (não remove nav/rodapé/menus) —
 * é suficiente para o downstream (`ResponseComposer.synthesizeResearch`, que
 * já filtra frases por termos do tópico) encontrar o dado pedido em meio ao
 * texto da página, o que o snippet da SERP nunca poderia conter.
 */
internal object ReadablePageTextExtractor {
    private val blocosSemTexto = Regex("(?is)<(script|style|noscript)[^>]*>.*?</\\1>")
    private const val MAX_CHARS = 20_000

    fun extract(html: String): String {
        val semBlocosOpacos = blocosSemTexto.replace(html, " ")
        val texto = HtmlTextDecoder.decode(semBlocosOpacos)
        return texto.replace(Regex("\\s+"), " ").trim().take(MAX_CHARS)
    }
}
