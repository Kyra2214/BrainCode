package com.sandbox.app

import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/** Seam de rede comum aos providers públicos: mockável em testes e sem retry escondido. */
data class ApiHttpResponse(val statusCode: Int, val body: String)

fun interface ApiHttpClient {
    fun get(url: String, headers: Map<String, String> = emptyMap()): ApiHttpResponse
}

class UrlConnectionApiHttpClient(
    private val timeoutMs: Int = 10_000,
    private val maxBodyChars: Int = 1_000_000
) : ApiHttpClient {
    override fun get(url: String, headers: Map<String, String>): ApiHttpResponse {
        require(URL(url).protocol.equals("https", ignoreCase = true)) { "APIs públicas exigem HTTPS" }
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json,text/plain;q=0.9,*/*;q=0.1")
            // Evita depender de decodificação gzip específica do aparelho/provedor.
            setRequestProperty("Accept-Encoding", "identity")
            setRequestProperty("User-Agent", "BrainCode/1.0 (Android app; public API client)")
            headers.forEach { (name, value) -> setRequestProperty(name, value) }
        }
        try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readTextLimited(maxBodyChars) }.orEmpty()
            return ApiHttpResponse(status, body)
        } finally {
            connection.disconnect()
        }
    }

    private fun BufferedReader.readTextLimited(maxChars: Int): String {
        val buffer = CharArray(8_192)
        val out = StringBuilder()
        while (out.length < maxChars) {
            val read = read(buffer, 0, minOf(buffer.size, maxChars - out.length))
            if (read < 0) break
            out.append(buffer, 0, read)
        }
        return out.toString()
    }
}

internal fun ApiHttpResponse.requireSuccess(service: String): String {
    if (statusCode !in 200..299) error("$service respondeu HTTP $statusCode")
    return body
}

internal fun String.encodeQuery(): String = java.net.URLEncoder.encode(this, Charsets.UTF_8.name())
