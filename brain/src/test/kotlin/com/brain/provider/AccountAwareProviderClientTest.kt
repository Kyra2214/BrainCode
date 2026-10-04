package com.brain.provider

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AccountAwareProviderClientTest {
    @Test
    fun `provider request preserva tools sem misturar credenciais`() {
        val tool = ProviderToolDefinition(
            name = "weather",
            description = "Consulta clima atual",
            parametersJson = "{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"}},\"required\":[\"city\"]}"
        )
        val request = ProviderRequest("model-a", "qual o clima", accountId = "account-a", tools = listOf(tool))
        assertEquals("weather", request.tools.single().name)
        assertEquals("object", org.json.JSONObject(request.tools.single().parametersJson).optString("type"))
    }

    @Test
    fun `tool call exige argumentos JSON objeto`() {
        val call = ProviderToolCall("call-1", "weather", "{\"city\":\"Niterói\"}")
        assertEquals("weather", call.name)
        assertEquals("Niterói", org.json.JSONObject(call.argumentsJson).optString("city"))
        assertFailsWith<IllegalArgumentException> { ProviderToolCall("call-2", "weather", "[]") }
    }

    @Test
    fun `resolve credencial apenas para accountId e nao expõe segredo ao caller`() {
        var received: ProviderRequest? = null
        val delegate = object : ProviderClient {
            override fun complete(request: ProviderRequest): Result<ProviderResponse> {
                received = request
                return Result.success(ProviderResponse(200, "ok", 4, "provider-a"))
            }
        }
        val client = AccountAwareProviderClient(delegate, "provider-a") { accountId, providerId ->
            assertEquals("account-a", accountId)
            assertEquals("provider-a", providerId)
            mapOf("Authorization" to "Bearer secret-not-in-brain")
        }

        val result = client.complete(ProviderRequest("model-a", "prompt", mapOf("Authorization" to "caller-value", "X-Api-Key" to "caller-key", "X-Trace" to "trace"), "account-a"))

        assertTrue(result.isSuccess)
        assertEquals("account-a", received?.accountId)
        assertEquals("Bearer secret-not-in-brain", received?.headers?.get("Authorization"))
        assertEquals(null, received?.headers?.get("X-Api-Key"))
        assertEquals("trace", received?.headers?.get("X-Trace"))
    }

    @Test
    fun `accountId ausente impede chamada ao provider`() {
        var called = false
        val client = AccountAwareProviderClient(
            delegate = object : ProviderClient {
                override fun complete(request: ProviderRequest): Result<ProviderResponse> {
                    called = true
                    return Result.success(ProviderResponse(200, "ok", 1, "provider-a"))
                }
            },
            providerId = "provider-a",
            credentials = CredentialProvider { _, _ -> emptyMap() }
        )

        val result = client.complete(ProviderRequest("model-a", "prompt"))

        assertTrue(result.isFailure)
        assertTrue(!called)
    }

    @Test
    fun `credential provider nao pode injetar accountId como header`() {
        val client = AccountAwareProviderClient(
            delegate = object : ProviderClient {
                override fun complete(request: ProviderRequest) = Result.success(ProviderResponse(200, "ok", 1, "provider-a"))
            },
            providerId = "provider-a",
            credentials = CredentialProvider { _, _ -> mapOf("accountId" to "account-a") }
        )

        assertFailsWith<IllegalArgumentException> {
            client.complete(ProviderRequest("model-a", "prompt", accountId = "account-a"))
        }
    }
}
