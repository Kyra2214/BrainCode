package com.brain.provider

import com.brain.router.InMemoryApiCatalog
import com.brain.router.JanelaLimite
import com.brain.router.PapelPipeline
import com.brain.router.ProviderModel
import com.brain.router.ToolCallingAudit
import com.brain.router.ToolArgumentPolicy
import com.brain.router.ToolSupportStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderDispatcherTest {
    private val tool = ToolDefinition(
        name = "weather",
        description = "Consulta clima",
        parametersJson = "{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"}},\"required\":[\"city\"]}"
    )

    private fun model(audited: Boolean = true) = ProviderModel(
        "p", "m", listOf(PapelPipeline.PLANEJAMENTO), JanelaLimite(),
        toolCalling = ToolCallingAudit(
            request = ToolSupportStatus.CONFIRMED,
            response = ToolSupportStatus.CONFIRMED,
            modelVerified = audited,
            arguments = ToolArgumentPolicy.CLIENT_VALIDATES,
            fallbackInstruction = "responda apenas texto"
        )
    )

    @Test fun `dispatch registra resposta textual normal`() {
        val catalog = InMemoryApiCatalog(listOf(model()))
        val client = RecordingClient(ProviderResponse(200, "texto", 3, "p"))
        assertEquals(200, ProviderDispatcher(catalog).dispatch(model(), client, ProviderRequest("ignored", "hello")).getOrThrow().statusCode)
        assertEquals("hello", client.requests.single().prompt)
        assertTrue(client.requests.single().tools.isEmpty())
    }

    @Test fun `dispatch envia uma tool quando modelo foi confirmado`() {
        val catalog = InMemoryApiCatalog(listOf(model()))
        val client = RecordingClient(ProviderResponse(200, "{\"tool_calls\":[{\"name\":\"weather\"}]}", 3, "p"))
        ProviderDispatcher(catalog).dispatch(model(), client, ProviderRequest("ignored", "tempo", tools = listOf(tool), toolChoice = "auto"))
        assertEquals(listOf("weather"), client.requests.single().tools.map { it.name })
        assertEquals("auto", client.requests.single().toolChoice)
    }

    @Test fun `dispatch envia multiplas tools`() {
        val catalog = InMemoryApiCatalog(listOf(model()))
        val client = RecordingClient(ProviderResponse(200, "{\"tool_calls\":[{},{}]}", 3, "p"))
        ProviderDispatcher(catalog).dispatch(model(), client, ProviderRequest("ignored", "pedido", tools = listOf(tool, tool.copy(name = "calendar"))))
        assertEquals(2, client.requests.single().tools.size)
    }

    @Test fun `validador rejeita argumentos invalidos antes da execucao`() {
        val valid = ToolCallValidator.validate(ParsedToolCall("weather", "{\"city\":\"São Paulo\"}"), listOf(tool))
        val invalid = ToolCallValidator.validate(ParsedToolCall("weather", "{\"wrong\":true}"), listOf(tool))
        assertTrue(valid.isSuccess)
        assertTrue(invalid.isFailure)
    }

    @Test fun `erro HTTP e registrado sem virar sucesso`() {
        val catalog = InMemoryApiCatalog(listOf(model()))
        val client = RecordingClient(ProviderResponse(500, "server error", 3, "p"))
        val result = ProviderDispatcher(catalog).dispatch(model(), client, ProviderRequest("ignored", "hello"))
        assertEquals(500, result.getOrThrow().statusCode)
        assertEquals(0.0, catalog.statsAtuais("p", "m")!!.taxaSucessoRecente!!, 0.001)
    }

    @Test fun `provider que rejeita tools recebe fallback textual especifico`() {
        val catalog = InMemoryApiCatalog(listOf(model()))
        val client = object : ProviderClient {
            val requests = mutableListOf<ProviderRequest>()
            override fun complete(request: ProviderRequest): Result<ProviderResponse> {
                requests += request
                return if (requests.size == 1) Result.success(ProviderResponse(400, "unsupported tool_choice", 2, "p"))
                else Result.success(ProviderResponse(200, "texto seguro", 2, "p"))
            }
        }
        val result = ProviderDispatcher(catalog).dispatchWithFallback(model(), client, ProviderRequest("ignored", "pedido", tools = listOf(tool), toolChoice = "required"))
        assertEquals(200, result.getOrThrow().statusCode)
        assertEquals(2, client.requests.size)
        assertTrue(client.requests[1].tools.isEmpty())
        assertTrue(client.requests[1].prompt.contains("responda apenas texto"))
    }

    @Test fun `modelo nao auditado nao recebe tools`() {
        val catalog = InMemoryApiCatalog(listOf(model(audited = false)))
        val client = RecordingClient(ProviderResponse(200, "texto", 3, "p"))
        ProviderDispatcher(catalog).dispatch(model(audited = false), client, ProviderRequest("ignored", "pedido", tools = listOf(tool), toolChoice = "required"))
        assertTrue(client.requests.single().tools.isEmpty())
        assertFalse(client.requests.single().toolChoice == "required")
    }

    private class RecordingClient(private val response: ProviderResponse) : ProviderClient {
        val requests = mutableListOf<ProviderRequest>()
        override fun complete(request: ProviderRequest): Result<ProviderResponse> {
            requests += request
            return Result.success(response)
        }
    }
}
