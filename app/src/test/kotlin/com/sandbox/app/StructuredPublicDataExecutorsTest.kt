package com.sandbox.app

import com.brain.capability.CapabilityRegistry
import com.brain.execution.RiskClass
import com.brain.gateway.ActionRequest
import com.brain.gateway.PolicyContext
import com.brain.policy.ApprovalRequired
import com.brain.policy.Decision
import com.brain.policy.PolicyDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StructuredPublicDataExecutorsTest {
    private class FakeHttp(vararg replies: ApiHttpResponse) : ApiHttpClient {
        val urls = mutableListOf<String>()
        private val responses = ArrayDeque(replies.toList())
        override fun get(url: String, headers: Map<String, String>): ApiHttpResponse {
            urls += url
            return responses.removeFirstOrNull() ?: error("unexpected extra request: $url")
        }
    }
    private fun response(code: Int, body: String) = ApiHttpResponse(code, body)
    private val capabilityProvider = PublicDataCapabilityProvider()
    private fun capability(id: String) = capabilityProvider.capabilities().first { it.id == id }
    private fun request(id: String, query: String) = ActionRequest(
        actionId = "test-$id", actor = "unit-test", capability = id,
        parameters = mapOf("parameter.0" to query, "conversationId" to "test-thread"),
        context = PolicyContext(runId = "test-run", taskId = "test-task", actor = "unit-test", networkAllowed = true)
    )
    private val allow = PolicyDecision(
        decisionId = "test-allow", runId = "test-run", taskId = "test-task", actor = "unit-test",
        capability = "test", riskClass = RiskClass.LOW, decision = Decision.ALLOW,
        approvalRequired = ApprovalRequired.NONE, sandboxRequired = true, networkAllowed = true,
        filesystemRoots = emptyList(), budget = emptyMap(), expiresAt = java.time.Instant.now().plusSeconds(300).toString(),
        reason = "unit test"
    )

    @Test fun `registry descobre capabilities publicas com origem e sem LLM`() {
        val registry = CapabilityRegistry(capabilityProvider.capabilities().toList())
        listOf("br.dados", "br.economia", "br.geografia", "weather", "cambio").forEach { id ->
            assertEquals(id, registry.getById(id)?.id)
            assertTrue(registry.getById(id)!!.provenance.isNotEmpty())
        }
        assertEquals(5, registry.all().size)
    }

    @Test fun `BrasilAPI de banco retorna resposta derivada do JSON e URL como evidencia`() {
        val http = FakeHttp(response(200, """{"code":341,"name":"ITAÚ UNIBANCO S.A.","fullName":"Banco Itaú Unibanco S.A."}"""))
        val execution = BrasilApiExecutor(http).execute(request("br.dados", "Qual o código do banco 341?"), capability("br.dados"), allow)
        assertTrue(execution.success)
        assertTrue(execution.userResponse!!.text.contains("ITAÚ UNIBANCO S.A."))
        assertTrue(execution.evidence.any { it.contains("https://brasilapi.com.br/api/banks/v1/341") })
        assertTrue(http.urls.single().endsWith("/341"))
    }

    @Test fun `CEP tenta ViaCEP somente se BrasilAPI falha e evidencia endpoint usado`() {
        val http = FakeHttp(response(404, "not found"))
        val fallback = FakeHttp(response(200, """{"cep":"01310-100","logradouro":"Avenida Paulista","bairro":"Bela Vista","localidade":"São Paulo","uf":"SP","erro":false}"""))
        val execution = BrasilApiExecutor(http, fallback).execute(request("br.dados", "Qual o CEP 01310-100?"), capability("br.dados"), allow)
        assertTrue(execution.success)
        assertTrue(execution.userResponse!!.text.contains("Avenida Paulista"))
        assertTrue(execution.userResponse!!.text.contains("São Paulo"))
        assertTrue(execution.evidence.any { it.contains("https://viacep.com.br/ws/01310100/json/") })
        assertEquals(1, fallback.urls.size)
    }

    @Test fun `BrasilAPI nao transforma lookup vazio em sucesso generico`() {
        val http = FakeHttp(response(404, "{}"))
        val execution = BrasilApiExecutor(http).execute(request("br.dados", "cep 99999999"), capability("br.dados"), allow)
        assertFalse(execution.success)
        assertTrue(execution.error!!.contains("ViaCEP"))
        assertEquals(null, execution.userResponse)
    }

    @Test fun `BCB consulta serie oficial SELIC e nao inventa valor`() {
        val http = FakeHttp(response(200, """[{"data":"25/09/2026","valor":"15.00"}]"""))
        val execution = BcbExecutor(http).execute(request("br.economia", "Qual a taxa Selic atual?"), capability("br.economia"), allow)
        assertTrue(execution.success)
        assertTrue(execution.userResponse!!.text.contains("15.00"))
        assertTrue(http.urls.single().contains("bcdata.sgs.432"))
        assertTrue(execution.evidence.any { it.contains("api.bcb.gov.br") })
    }

    @Test fun `Open-Meteo geocodifica antes de forecast e responde sem synth LLM`() {
        val http = FakeHttp(
            response(200, """{"results":[{"name":"Macaé","admin1":"Rio de Janeiro","latitude":-22.37,"longitude":-41.78,"country_code":"BR"}]}"""),
            response(200, """{"current":{"temperature_2m":28.0,"apparent_temperature":29.1,"relative_humidity_2m":72,"precipitation":0.0,"weather_code":2,"wind_speed_10m":11.2},"current_units":{"temperature_2m":"°C","apparent_temperature":"°C","relative_humidity_2m":"%","precipitation":"mm","wind_speed_10m":"km/h"}}""")
        )
        val execution = WeatherExecutor(http).execute(request("weather", "Qual o tempo em Macaé RJ?"), capability("weather"), allow)
        assertTrue(execution.success)
        assertTrue(execution.userResponse!!.text.contains("Macaé"))
        assertTrue(execution.userResponse!!.text.contains("28.0°C"))
        assertTrue(execution.userResponse!!.text.contains("parcialmente nublado"))
        assertEquals(2, http.urls.size)
        assertTrue(http.urls[0].contains("geocoding-api.open-meteo.com"))
        assertTrue(http.urls[1].contains("api.open-meteo.com/v1/forecast"))
    }

    @Test fun `Open-Meteo nao envia a sigla da UF dentro do name do geocoding`() {
        // Regressão: "Macaé RJ" mandado como name= literal não bate no geocoding real do
        // Open-Meteo (o campo name só aceita o nome do lugar). A UF deve virar filtro de
        // admin1, nunca parte do texto buscado.
        val http = FakeHttp(
            response(200, """{"results":[{"name":"Macaé","admin1":"Rio de Janeiro","latitude":-22.37,"longitude":-41.78,"country_code":"BR"}]}"""),
            response(200, """{"current":{"temperature_2m":28.0,"apparent_temperature":29.1,"relative_humidity_2m":72,"precipitation":0.0,"weather_code":2,"wind_speed_10m":11.2},"current_units":{"temperature_2m":"°C","apparent_temperature":"°C","relative_humidity_2m":"%","precipitation":"mm","wind_speed_10m":"km/h"}}""")
        )
        val execution = WeatherExecutor(http).execute(request("weather", "qual a previsão do tempo hoje em Macaé RJ"), capability("weather"), allow)
        assertTrue(execution.success)
        val geocodingUrl = http.urls[0]
        assertFalse("UF não pode ir dentro do name= do geocoding: $geocodingUrl", geocodingUrl.contains("Maca%C3%A9+RJ") || geocodingUrl.contains("Maca%C3%A9%20RJ"))
        assertTrue(geocodingUrl.contains("name=Maca"))
    }

    @Test fun `Open-Meteo desempata homonimos pela UF quando ha varios resultados`() {
        val http = FakeHttp(
            response(200, """{"results":[
                {"name":"Rio das Ostras","admin1":"Rio Grande do Sul","latitude":-1.0,"longitude":-1.0,"country_code":"BR"},
                {"name":"Rio das Ostras","admin1":"Rio de Janeiro","latitude":-22.53,"longitude":-41.95,"country_code":"BR"}
            ]}"""),
            response(200, """{"current":{"temperature_2m":30.0,"apparent_temperature":31.0,"relative_humidity_2m":70,"precipitation":0.0,"weather_code":0,"wind_speed_10m":9.0},"current_units":{"temperature_2m":"°C","apparent_temperature":"°C","relative_humidity_2m":"%","precipitation":"mm","wind_speed_10m":"km/h"}}""")
        )
        val execution = WeatherExecutor(http).execute(request("weather", "tempo em Rio das Ostras RJ agora"), capability("weather"), allow)
        assertTrue(execution.success)
        assertTrue(execution.userResponse!!.text.contains("Rio das Ostras - Rio de Janeiro"))
    }

    @Test fun `local sem preposicao em ou de ainda e reconhecido`() {
        val http = FakeHttp(
            response(200, """{"results":[{"name":"Rio das Ostras","admin1":"Rio de Janeiro","latitude":-22.53,"longitude":-41.95,"country_code":"BR"}]}"""),
            response(200, """{"current":{"temperature_2m":27.0,"apparent_temperature":28.0,"relative_humidity_2m":80,"precipitation":0.0,"weather_code":1,"wind_speed_10m":10.0},"current_units":{"temperature_2m":"°C","apparent_temperature":"°C","relative_humidity_2m":"%","precipitation":"mm","wind_speed_10m":"km/h"}}""")
        )
        val execution = WeatherExecutor(http).execute(request("weather", "qual a previsão do tempo hoje rio das ostras"), capability("weather"), allow)
        assertTrue(execution.success)
        assertTrue(http.urls[0].contains("name=rio"))
    }

    @Test fun `cambio tenta Frankfurter e usa fallback de taxa se necessario`() {
        val http = FakeHttp(response(429, "busy"))
        val fallback = FakeHttp(response(200, """{"date":"2026-09-25","brl":{"usd":0.193}}"""))
        val execution = ExchangeRateExecutor(http, fallback).execute(request("cambio", "Converta 100 reais para dólar"), capability("cambio"), allow)
        assertTrue(execution.success)
        assertTrue(execution.userResponse!!.text.contains("19,3 USD"))
        assertTrue(http.urls.single().contains("/v2/rate/brl/usd"))
        assertTrue(fallback.urls.single().contains("currency-api@latest"))
        assertTrue(execution.evidence.any { it.contains("currency-api@latest") })
    }

    @Test fun `timeout retorna falha explicita sem user response com dado inventado`() {
        val http = object : ApiHttpClient {
            override fun get(url: String, headers: Map<String, String>): ApiHttpResponse = throw java.net.SocketTimeoutException("timeout")
        }
        val execution = WeatherExecutor(http).execute(request("weather", "Tempo em Macaé"), capability("weather"), allow)
        assertFalse(execution.success)
        assertTrue(execution.error!!.contains("timeout"))
        assertEquals(null, execution.userResponse)
    }
}
