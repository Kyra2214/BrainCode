package com.brain.provider

import com.brain.router.ApiCatalog
import com.brain.router.ProviderModel
import com.brain.router.TipoErro
import java.time.Instant

class ProviderDispatcher(private val catalog: ApiCatalog) {
    /**
     * Despacha uma chamada. Tools só atravessam a fronteira se a matriz tiver
     * confirmado request e response para o modelo concreto; ausência de auditoria
     * é sempre tratada como incompatibilidade.
     */
    fun dispatch(model: ProviderModel, client: ProviderClient, request: ProviderRequest): Result<ProviderResponse> {
        val safeRequest = request.copy(
            model = model.modeloId,
            tools = request.tools.takeIf { model.toolCalling.allowsTools() } ?: emptyList(),
            toolChoice = request.toolChoice.takeIf { model.toolCalling.allowsTools() }
        )
        val result = client.complete(safeRequest)
        record(model, result)
        return result
    }

    /**
     * Se o provider rejeitar especificamente o payload de tools, tenta uma única
     * resposta textual com instrução específica do provider. Texto nunca vira
     * chamada executável; o executor deve continuar validando/autorizando ações.
     */
    fun dispatchWithFallback(
        model: ProviderModel,
        client: ProviderClient,
        request: ProviderRequest
    ): Result<ProviderResponse> {
        val first = dispatch(model, client, request)
        val response = first.getOrNull()
        if (request.tools.isEmpty() || response == null || !isToolCapabilityError(response)) return first

        val fallbackRequest = request.copy(
            model = model.modeloId,
            prompt = request.prompt + "\n\n" + model.toolCalling.fallbackInstruction,
            tools = emptyList(),
            toolChoice = null
        )
        val fallback = client.complete(fallbackRequest)
        record(model, fallback)
        return fallback
    }

    private fun isToolCapabilityError(response: ProviderResponse): Boolean {
        if (response.statusCode !in 400..499) return false
        val body = response.body.lowercase()
        return listOf("tool", "function_call", "tool_choice", "unsupported parameter", "not support")
            .any(body::contains)
    }

    private fun record(model: ProviderModel, result: Result<ProviderResponse>) {
        result.onSuccess { response ->
            val ok = response.statusCode in 200..299
            catalog.registrarResultado(
                model.providerId,
                model.modeloId,
                ok,
                response.latencyMs,
                if (ok) null else response.statusCode.toObservedError()
            )
        }.onFailure {
            catalog.registrarResultado(
                model.providerId,
                model.modeloId,
                false,
                0L,
                TipoErro.TIMEOUT.toObserved()
            )
        }
    }

    private fun Int.toObservedError(): com.brain.router.ErroObservado =
        when (this) {
            400, 422 -> TipoErro.REQUISICAO_INVALIDA
            401 -> TipoErro.CHAVE_INVALIDA
            403 -> TipoErro.POLICY_NEGADA
            429 -> TipoErro.LIMITE_ATINGIDO
            408, 504 -> TipoErro.TIMEOUT
            in 500..599 -> TipoErro.ERRO_SERVIDOR
            else -> TipoErro.DESCONHECIDO
        }.toObserved()

    private fun TipoErro.toObserved() = com.brain.router.ErroObservado(this, Instant.now())
}
