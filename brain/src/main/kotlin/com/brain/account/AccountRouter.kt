package com.brain.account

import java.time.Instant

data class AccountRouteRequest(
    val executionId: String,
    val capability: String,
    val pool: AccountPool,
    val authorizedAccountIds: Set<String>,
    val idempotent: Boolean,
    val requestedModelId: String? = null
) {
    init {
        require(executionId.isNotBlank()) { "executionId é obrigatório" }
        require(capability.isNotBlank()) { "capability é obrigatória" }
        require(authorizedAccountIds.none { it.isBlank() }) { "accountId autorizado não pode ser vazio" }
    }
}

data class AccountRouteCandidate(
    val accountId: String,
    val providerId: String
)

sealed interface AccountRouteDecision {
    val executionId: String

    data class Selected(
        override val executionId: String,
        val accountId: String,
        val providerId: String,
        val requestedModelId: String?,
        val attemptNumber: Int,
        val reasonCodes: List<String>,
        val alternatives: List<AccountRouteCandidate> = emptyList()
    ) : AccountRouteDecision

    data class Unavailable(
        override val executionId: String,
        val reasonCodes: List<String>
    ) : AccountRouteDecision
}

/** Decide uma conta lógica; não executa provider, não lê credencial e não concede policy. */
class AccountRouter {
    fun route(request: AccountRouteRequest, now: Instant): AccountRouteDecision {
        val candidates = request.pool.candidates(
            now = now,
            idempotent = request.idempotent,
            authorizedAccountIds = request.authorizedAccountIds
        )

        val selected = candidates.firstOrNull()
            ?: return AccountRouteDecision.Unavailable(
                executionId = request.executionId,
                reasonCodes = buildList {
                    if (request.pool.members.isEmpty()) add("POOL_EMPTY")
                    if (request.pool.members.none { it.capabilities.contains(request.capability) }) add("CAPABILITY_UNAVAILABLE")
                    if (request.pool.members.any { !it.health.canSelect(now) }) add("NO_HEALTHY_ACCOUNT")
                    if (request.pool.members.isNotEmpty() && request.pool.members.none { it.accountId in request.authorizedAccountIds }) add("POLICY_NO_AUTHORIZED_ACCOUNT")
                    add("NO_ELIGIBLE_ACCOUNT")
                }
            )

        return AccountRouteDecision.Selected(
            executionId = request.executionId,
            accountId = selected.accountId,
            providerId = selected.providerId,
            requestedModelId = request.requestedModelId,
            attemptNumber = 1,
            reasonCodes = listOf("PRIORITY", "HEALTHY", "AUTHORIZED"),
            alternatives = candidates.drop(1).map { AccountRouteCandidate(it.accountId, it.providerId) }
        )
    }
}
