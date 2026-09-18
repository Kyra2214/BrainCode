package com.brain.provider

/**
 * Resolve credenciais dentro da camada de provider. O contrato retorna headers
 * somente para a chamada imediata e nunca é exposto ao Brain/Planner/Router.
 */
fun interface CredentialProvider {
    fun headersFor(accountId: String, providerId: String): Map<String, String>
}

class AccountAwareProviderClient(
    private val delegate: ProviderClient,
    private val providerId: String,
    private val credentials: CredentialProvider
) : ProviderClient {
    override fun complete(request: ProviderRequest): Result<ProviderResponse> {
        val accountId = request.accountId ?: return Result.failure(
            IllegalArgumentException("accountId é obrigatório para provider account-aware")
        )
        val credentialHeaders = credentials.headersFor(accountId, providerId)
        require(credentialHeaders.keys.none { it.equals("accountId", ignoreCase = true) }) {
            "CredentialProvider não pode devolver accountId como header"
        }
        return delegate.complete(
            request.copy(headers = request.headers + credentialHeaders, accountId = accountId)
        )
    }
}
