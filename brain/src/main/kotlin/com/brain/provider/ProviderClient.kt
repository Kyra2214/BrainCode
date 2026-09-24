package com.brain.provider

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

data class ProviderRequest(
    val model: String,
    val prompt: String,
    val headers: Map<String, String> = emptyMap(),
    val accountId: String? = null
)
data class ProviderResponse(val statusCode: Int, val body: String, val latencyMs: Long, val providerId: String)

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
        val body = "{\"model\":${json(request.model)},\"prompt\":${json(request.prompt)}}"
        val builder = HttpRequest.newBuilder(endpoint).timeout(timeout).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body))
        request.headers.filterKeys { it.lowercase() !in setOf("host", "content-length") }.forEach { (key, value) -> builder.header(key, value) }
        val response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        ProviderResponse(response.statusCode(), response.body(), (System.nanoTime() - started) / 1_000_000, providerId)
    }

    private fun json(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
}
