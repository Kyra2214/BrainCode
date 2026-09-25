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
 * Extrai texto legível de um HTML bruto.
 *
 * Extração leve por regex, na mesma linha de `DuckDuckGoWebResearchProvider`:
 * evita depender de um parser HTML completo (custo de RAM/APK em Android).
 *
 * Duas falhas foram observadas em produção com esta abordagem simplista e
 * ambas são corrigidas aqui:
 *
 * 1) Navegação, menus, TOC de Wikipédia, rodapé etc. eram incluídos junto do
 *    conteúdo principal — regex não distinguia "artigo" de "chrome da página".
 *    Corrige-se removendo blocos inteiros de tags estruturais conhecidas
 *    (`nav`, `header`, `footer`, `aside`, `form`) e de qualquer elemento cujo
 *    `id`/`class` indique explicitamente ser navegação/menu/TOC/propaganda/etc.
 *
 * 2) Ao remover tags sem inserir nenhum separador de frase, itens de menu
 *    adjacentes (ex.: `<li>Clima</li><li>Notícias</li>`) viravam uma única
 *    "frase" gigante sem pontuação — o que fazia `ResponseComposer.
 *    synthesizeResearch` (que filtra por frase) tratar a página inteira como
 *    uma frase só, incapaz de aplicar seu próprio filtro de boilerplate.
 *    Corrige-se inserindo um ponto final ao fechar tags de bloco (`p`, `li`,
 *    `div`, `tr`, `h1`-`h6`, `br` etc.) antes de descartar as tags, e dando
 *    preferência ao texto dentro de `<p>` quando houver parágrafos
 *    suficientes — a heurística mais confiável para "isto é o artigo, não o
 *    menu" sem um parser de árvore DOM completo.
 *
 * Continua sem tentar ser um extrator perfeito de "conteúdo principal": o
 * downstream (`ResponseComposer.synthesizeResearch`) ainda filtra frases por
 * termos do tópico e por boilerplate residual. Isto só garante que ele
 * receba frases de verdade para filtrar, em vez de um blob único.
 */
internal object ReadablePageTextExtractor {
    private val blocosSemTexto = Regex("(?is)<(script|style|noscript|template)[^>]*>.*?</\\1>")

    /** Tags cujo conteúdo inteiro é "chrome" da página, nunca conteúdo do artigo. */
    private val tagsEstruturais = listOf("nav", "header", "footer", "aside", "form", "button", "select")
    private val blocosEstruturais = tagsEstruturais.map { tag ->
        Regex("(?is)<$tag\\b[^>]*>.*?</$tag>")
    }

    /** Elementos de qualquer tag cujo id/class denuncia navegação/menu/propaganda/etc. */
    private val palavrasRuido = listOf(
        "nav", "menu", "sidebar", "footer", "header", "cookie", "banner",
        "breadcrumb", "toc", "table-of-contents", "widget", "advert", "\\bads\\b",
        "subscribe", "newsletter", "social-share", "share-buttons", "login",
        "signup", "infobox", "navbox", "catlinks", "printfooter", "editsection",
        "portal", "mw-portlet", "vector-menu", "site-header", "site-footer"
    ).joinToString("|")
    private val blocoComIdOuClasseDeRuido = Regex(
        "(?is)<([a-zA-Z0-9]+)\\b(?=[^>]*\\b(?:id|class)\\s*=\\s*\"[^\"]*(?:$palavrasRuido)[^\"]*\")[^>]*>.*?</\\1>"
    )

    /** Fechamentos de tags de bloco viram fim de frase antes de as tags serem descartadas. */
    private val fechamentosDeBloco = Regex(
        "(?is)</(p|li|div|tr|td|th|h1|h2|h3|h4|h5|h6|section|article|blockquote)\\s*>|<br\\s*/?>"
    )

    private val paragrafos = Regex("(?is)<p\\b[^>]*>(.*?)</p>")
    private const val MAX_CHARS = 20_000
    private const val MIN_CHARS_PARA_USAR_PARAGRAFOS = 200

    fun extract(html: String): String {
        var semRuido = blocosSemTexto.replace(html, " ")
        blocosEstruturais.forEach { semRuido = it.replace(semRuido, " ") }
        // Aplicado repetidamente: remoção de blocos aninhados (ex.: div de ruído dentro de outra)
        // não é resolvida numa única passada por um regex não-recursivo.
        repeat(3) { semRuido = blocoComIdOuClasseDeRuido.replace(semRuido, " ") }

        val textoDosParagrafos = paragrafos.findAll(semRuido)
            .joinToString(" ") { HtmlTextDecoder.decode(it.groupValues[1]) }
            .replace(Regex("\\s+"), " ")
            .trim()

        val texto = if (textoDosParagrafos.length >= MIN_CHARS_PARA_USAR_PARAGRAFOS) {
            textoDosParagrafos
        } else {
            val comFimDeFrase = fechamentosDeBloco.replace(semRuido) { ". " }
            HtmlTextDecoder.decode(comFimDeFrase).replace(Regex("\\s+"), " ").trim()
        }
        return texto.take(MAX_CHARS)
    }
}
