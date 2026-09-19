package com.brain.account

import com.brain.provider.ProviderRegistry
import java.time.Instant

data class ProviderAccountRouteRequest(
    val executionId: String,
    val capability: String,
    val pool: AccountPool,
    val authorizedAccountIds: Set<String>,
    val idempotent: Boolean,
    val requestedModelId: String? = null
)

/** Compõe os registries existentes sem expor credentialRef ao executor ou provider client. */
class ProviderAccountRouter(
    private val providers: ProviderRegistry,
    private val accounts: AccountRegistry,
    private val accountRouter: AccountRouter = AccountRouter()
) {
    fun route(request: ProviderAccountRouteRequest, now: Instant = Instant.now()): AccountRouteDecision {
        val registered = providers.list(enabledOnly = true)
            .filter { it.supports(request.capability) }
            .associateBy { it.providerId }
        val eligible = request.pool.members
            .filter { it.accountId in request.authorizedAccountIds }
            .filter { it.providerId in registered }
            .filter { accounts.find(it.accountId)?.isEligible(request.capability, now) == true }
            .mapNotNull { accounts.find(it.accountId) }
        val filteredPool = request.pool.copy(members = eligible)
        return accountRouter.route(
            AccountRouteRequest(
                executionId = request.executionId,
                capability = request.capability,
                pool = filteredPool,
                authorizedAccountIds = eligible.map { it.accountId }.toSet(),
                idempotent = request.idempotent,
                requestedModelId = request.requestedModelId
            ),
            now
        )
    }
}
