package com.sandbox.app

import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

sealed interface ApiKeyTestOutcome {
    data class Success(val statusCode: Int, val latencyMs: Long) : ApiKeyTestOutcome
    data class Failure(val message: String) : ApiKeyTestOutcome
}

/**
 * Testa uma chave de API com uma chamada real e mínima (poucos tokens),
 * chamada síncrona — o chamador é responsável por rodar isto fora da
 * main thread (ex.: Dispatchers.IO).
 *
 * Todos os providers do catálogo (ver ai_api_catalog.json) documentam
 * compatibilidade com o formato OpenAI `/chat/completions`, então este
 * teste usa esse contrato comum: `POST {endpoint}/chat/completions` com
 * `Authorization: Bearer <chave>`. Isso cobre a maioria dos casos, mas
 * não é garantia formal por provider — um HTTP diferente de 2xx é
 * reportado com o status e um trecho do corpo da resposta, pra dar
 * contexto suficiente pro usuário diagnosticar (chave errada vs. corpo
 * de request que aquele provider específico não aceitou).
 */
object ApiKeyTester {
    fun test(endpoint: String, modelId: String, apiKey: String, timeoutMs: Int = 15_000): ApiKeyTestOutcome {
        val url = endpoint.trimEnd('/') + "/chat/completions"
        val startedAt = System.currentTimeMillis()
        return try {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer $apiKey")
            }
            val body = """
                {"model":${jsonString(modelId)},"messages":[{"role":"user","content":"ping"}],"max_tokens":5}
            """.trimIndent()
            OutputStreamWriter(connection.outputStream).use { it.write(body) }

            val statusCode = connection.responseCode
            val latencyMs = System.currentTimeMillis() - startedAt
            if (statusCode in 200..299) {
                connection.inputStream.close()
                ApiKeyTestOutcome.Success(statusCode, latencyMs)
            } else {
                val errorBody = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                val snippet = errorBody.take(200)
                val reason = when (statusCode) {
                    401, 403 -> "chave inválida, sem permissão ou expirada"
                    404 -> "endpoint /chat/completions não existe para este provider (formato de API diferente do esperado)"
                    429 -> "limite de uso atingido — a chave parece válida"
                    in 500..599 -> "erro no servidor do provider (tente de novo mais tarde)"
                    else -> "resposta inesperada"
                }
                ApiKeyTestOutcome.Failure("HTTP $statusCode — $reason${if (snippet.isNotBlank()) ": $snippet" else ""}")
            }
        } catch (e: Exception) {
            ApiKeyTestOutcome.Failure("Falha de conexão: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun jsonString(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
