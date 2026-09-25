package com.sandbox.app

import com.brain.research.ResearchIntentClassifier
import com.brain.research.ResearchIntentKind
import com.brain.research.ResearchRequest
import com.brain.research.SearchProvider
import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StructuredResearchProvidersTest {
    private class FakeHttp(vararg replies: ApiHttpResponse) : ApiHttpClient {
        val urls = mutableListOf<String>()
        val headers = mutableListOf<Map<String, String>>()
        private val responses = ArrayDeque(replies.toList())
        override fun get(url: String, headers: Map<String, String>): ApiHttpResponse {
            urls += url
            this.headers += headers
            val next = responses.removeFirstOrNull() ?: throw SocketTimeoutException("timeout fake")
            if (next.statusCode == -1) throw SocketTimeoutException(next.body)
            return next
        }
    }
    private fun ok(json: String) = ApiHttpResponse(200, json)
    private fun request(q: String, max: Int = 3) = ResearchRequest(q, constraints = com.brain.research.ResearchConstraints(maxSources = max))

    @Test fun `Brave mapeia resultados reais e nao inclui chave na URL`() {
        val http = FakeHttp(ok("""{"web":{"results":[{"title":"Ktor CORS","url":"https://ktor.io/docs/server-cors.html","description":"Configuração oficial de CORS."}]}}"""))
        val result = BraveSearchWebResearchProvider({ "test-token-never-log" }, http).search(request("CORS no Ktor")).getOrThrow().single()
        assertEquals("ktor.io", result.source)
        assertEquals("https://ktor.io/docs/server-cors.html", result.url)
        assertTrue(http.urls.single().contains("q=CORS+no+Ktor"))
        assertFalse(http.urls.single().contains("test-token"))
        assertEquals("test-token-never-log", http.headers.single()["X-Subscription-Token"])
    }

    @Test fun `Brave sem key 401 403 timeout parse e zero falham explicitamente`() {
        assertTrue(BraveSearchWebResearchProvider({ null }, FakeHttp()).search(request("a")).isFailure)
        listOf(
            ApiHttpResponse(401, "{}"), ApiHttpResponse(403, "{}"), ApiHttpResponse(200, "not json"),
            ok("""{"web":{"results":[]}}"""), ApiHttpResponse(-1, "read timeout")
        ).forEach { response ->
            assertTrue(BraveSearchWebResearchProvider({ "fake" }, FakeHttp(response)).search(request("query")).isFailure)
        }
    }

    @Test fun `roteamento tecnico factico academico e geral e serial com fallback Brave DDG`() {
        val visited = mutableListOf<String>()
        fun provider(id: String, output: Result<List<com.brain.research.ResearchResult>>) = SearchProvider { visited += id; output }
        val actual = com.brain.research.ResearchResult("q", "docs.test", "title", "https://docs.test", "real evidence", java.time.Instant.now())
        val router = ResearchSearchRoutingProvider(
            brave = provider("brave", Result.success(listOf(actual))),
            duckDuckGo = provider("ddg", Result.success(listOf(actual.copy(source = "ddg.test", url = "https://ddg.test")))),
            wikidata = provider("wikidata", Result.failure(IllegalStateException("empty"))),
            stackExchange = provider("stack", Result.failure(IllegalStateException("empty"))),
            openAlex = provider("openalex", Result.failure(IllegalStateException("empty")))
        )
        assertEquals(ResearchIntentKind.TECHNICAL, ResearchIntentClassifier.classify("como configurar CORS no Ktor"))
        assertEquals(ResearchIntentKind.FACTUAL, ResearchIntentClassifier.classify("quando nasceu Ada Lovelace"))
        assertEquals(ResearchIntentKind.ACADEMIC, ResearchIntentClassifier.classify("paper sobre Attention Is All You Need"))
        assertEquals(ResearchIntentKind.GENERAL, ResearchIntentClassifier.classify("pesquise sobre energia solar"))

        router.search(request("como configurar CORS no Ktor"))
        assertEquals(listOf("stack", "brave"), visited)
        visited.clear()
        router.search(request("quando nasceu Ada Lovelace"))
        assertEquals(listOf("wikidata", "brave"), visited)
        visited.clear()
        router.search(request("paper sobre Attention Is All You Need"))
        assertEquals(listOf("openalex", "brave"), visited)
        visited.clear()
        router.search(request("pesquise sobre energia solar"))
        assertEquals(listOf("brave"), visited)
    }

    @Test fun `router usa DuckDuckGo ao faltar chave Brave ou falhar provider e erro final e explicito`() {
        val item = com.brain.research.ResearchResult("q", "duckduckgo.com", "Real", "https://duckduckgo.com/result", "Snippet real", java.time.Instant.now())
        var ddgCalls = 0
        val router = ResearchSearchRoutingProvider(
            brave = BraveSearchWebResearchProvider({ null }, FakeHttp()),
            duckDuckGo = SearchProvider { ddgCalls++; Result.success(listOf(item)) },
            wikidata = SearchProvider { Result.failure(IllegalStateException("offline")) },
            stackExchange = SearchProvider { Result.failure(IllegalStateException("offline")) },
            openAlex = SearchProvider { Result.failure(IllegalStateException("offline")) }
        )
        assertEquals(item.url, router.search(request("assunto geral atual")).getOrThrow().single().url)
        assertEquals(1, ddgCalls)
        val noSources = ResearchSearchRoutingProvider(
            brave = SearchProvider { Result.failure(IllegalStateException("401")) },
            duckDuckGo = SearchProvider { Result.success(emptyList()) },
            wikidata = SearchProvider { Result.failure(IllegalStateException("offline")) },
            stackExchange = SearchProvider { Result.failure(IllegalStateException("offline")) },
            openAlex = SearchProvider { Result.failure(IllegalStateException("offline")) }
        ).search(request("query geral"))
        assertTrue(noSources.isFailure)
    }

    @Test fun `Wikidata conserva QID e inclui claim de nascimento somente quando retorno contem valor`() {
        val http = FakeHttp(
            ok("""{"search":[{"id":"Q7259","label":"Ada Lovelace","description":"matemática e escritora inglesa","concepturi":"https://www.wikidata.org/entity/Q7259"}]}"""),
            ok("""{"entities":{"Q7259":{"claims":{"P569":[{"mainsnak":{"datavalue":{"type":"time","value":{"time":"+1815-12-10T00:00:00Z"}}}}]}}}}""")
        )
        val result = WikidataSearchProvider(http).search(request("quando nasceu Ada Lovelace")).getOrThrow().single()
        assertEquals("https://www.wikidata.org/wiki/Q7259", result.url)
        assertTrue(result.relevantContent.contains("Q7259"))
        assertTrue(result.relevantContent.contains("1815-12-10"))
        assertEquals(2, http.urls.size)
    }

    @Test fun `StackExchange preserva titulo URL tags e score retornados pela API`() {
        val http = FakeHttp(ok("""{"items":[{"title":"Enable CORS in Ktor","link":"https://stackoverflow.com/questions/1/enable-cors","body_markdown":"Install and configure the CORS plugin.","tags":["ktor","cors"],"score":12}],"backoff":0,"quota_remaining":299}"""))
        val result = StackExchangeSearchProvider(http).search(request("como configurar CORS no Ktor")).getOrThrow().single()
        assertEquals("stackoverflow.com", result.source)
        assertTrue(result.relevantContent.contains("Tags: ktor, cors"))
        assertTrue(result.url.contains("/questions/1/"))
    }

    @Test fun `OpenAlex reconstrui abstract pelos offsets e preserva autores ano e DOI`() {
        val http = FakeHttp(ok("""{"results":[{"id":"https://openalex.org/W1","doi":"https://doi.org/10.1234/test","display_name":"Attention Is All You Need","publication_year":2017,"authorships":[{"author":{"display_name":"Ashish Vaswani"}}],"abstract_inverted_index":{"Transformers":[1],"We":[0],"replace":[2],"recurrence":[3]}}]}"""))
        val result = OpenAlexSearchProvider(http).search(request("paper sobre Attention Is All You Need")).getOrThrow().single()
        assertEquals("https://doi.org/10.1234/test", result.url)
        assertTrue(result.relevantContent.contains("We Transformers replace recurrence"))
        assertTrue(result.relevantContent.contains("Ashish Vaswani"))
        assertTrue(result.relevantContent.contains("2017"))
    }
}
