package com.brain.provider

import com.brain.router.ApiCatalog
import com.brain.router.ProviderModel
import com.brain.router.TipoErro
import java.time.Instant

class ProviderDispatcher(private val catalog: ApiCatalog) {
    fun dispatch(model: ProviderModel, client: ProviderClient, request: ProviderRequest): Result<ProviderResponse> {
        val result = client.complete(request.copy(model = model.modeloId))
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
        return result
    }

    private fun Int.toObservedError(): com.brain.router.ErroObservado =
        when (this) {
            401, 403 -> TipoErro.CHAVE_INVALIDA
            429 -> TipoErro.LIMITE_ATINGIDO
            408, 504 -> TipoErro.TIMEOUT
            in 500..599 -> TipoErro.ERRO_SERVIDOR
            else -> TipoErro.DESCONHECIDO
        }.toObserved()

    private fun TipoErro.toObserved() = com.brain.router.ErroObservado(this, Instant.now())
}
