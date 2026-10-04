package com.brain.provider

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

data class ProviderToolDefinition(
    val name: String,
    val description: String,
    /** JSON Schema do objeto de argumentos, mantido como texto para preservar o contrato do provider. */
    val parametersJson: String
) {
    init {
        require(name.matches(Regex("[a-zA-Z0-9_.-]{1,64}"))) { "nome de tool inválido" }
        require(description.isNotBlank()) { "descrição de tool não pode ser vazia" }
        require(JSONObject(parametersJson).optString("type") == "object") {
            "parametersJson deve ser um JSON Schema object"
        }
    }
}

data class ProviderToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String
) {
    init {
        require(id.isNotBlank()) { "id de tool call não pode ser vazio" }
        require(name.isNotBlank()) { "nome de tool call não pode ser vazio" }
        require(JSONObject(argumentsJson).toString().isNotBlank()) { "argumentos de tool call inválidos" }
    }
}

data class ProviderRequest(
    val model: String,
    val prompt: String,
    val headers: Map<String, String> = emptyMap(),
    val accountId: String? = null,
    val tools: List<ProviderToolDefinition> = emptyList()
)
data class ProviderResponse(
    val statusCode: Int,
    val body: String,
    val latencyMs: Long,
    val providerId: String,
    val toolCalls: List<ProviderToolCall> = emptyList()
)

interface ProviderClient {
    fun complete(request: ProviderRequest): Result<ProviderResponse>
}

/** Generic HTTP adapter. Secrets are supplied as headers at call time and never logged or persisted. */
class HttpProviderClient(
    private val providerId: String,
    private val endpoint: URI,
    private val timeout: Duration = Duration.ofSeconds(30),
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(timeout).build()
) : ProviderClient {
    override fun complete(request: ProviderRequest): Result<ProviderResponse> = runCatching {
        require(request.model.isNotBlank()) { "model não pode ser vazio" }
        require(request.prompt.isNotBlank()) { "prompt não pode ser vazio" }
        val started = System.nanoTime()
        val body = JSONObject().apply {
            put("model", request.model)
            put("prompt", request.prompt)
            if (request.tools.isNotEmpty()) {
                put("tools", JSONArray(request.tools.map { tool ->
                    JSONObject()
                        .put("type", "function")
                        .put("function", JSONObject()
                            .put("name", tool.name)
                            .put("description", tool.description)
                            .put("parameters", JSONObject(tool.parametersJson)))
                }))
            }
        }.toString()
        val builder = HttpRequest.newBuilder(endpoint).timeout(timeout)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
        request.headers.filterKeys { it.lowercase() !in setOf("host", "content-length") }
            .forEach { (key, value) -> builder.header(key, value) }
        val response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        ProviderResponse(
            response.statusCode(), response.body(), (System.nanoTime() - started) / 1_000_000,
            providerId, parseToolCalls(response.body())
        )
    }

    private fun parseToolCalls(body: String): List<ProviderToolCall> = runCatching {
        val choice = JSONObject(body).optJSONArray("choices")?.optJSONObject(0) ?: return emptyList()
        val calls = choice.optJSONObject("message")?.optJSONArray("tool_calls") ?: return emptyList()
        (0 until calls.length()).mapNotNull { index ->
            val call = calls.optJSONObject(index) ?: return@mapNotNull null
            val function = call.optJSONObject("function") ?: return@mapNotNull null
            val id = call.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val name = function.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val args = function.optString("arguments", "{}").takeIf { runCatching { JSONObject(it) }.isSuccess }
                ?: return@mapNotNull null
            ProviderToolCall(id, name, args)
        }
    }.getOrDefault(emptyList())
}
