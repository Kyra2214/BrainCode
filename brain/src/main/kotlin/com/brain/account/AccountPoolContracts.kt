package com.brain.account

import java.time.Duration
import java.time.Instant

/** Referência opaca; credenciais reais ficam no Provider/Credential layer. */
@JvmInline
value class CredentialRef private constructor(val value: String) {
    companion object {
        fun of(value: String): CredentialRef {
            require(value.startsWith("credential:")) { "credentialRef deve ser uma referência opaca" }
            require(value.length in 12..160) { "credentialRef inválida" }
            require(value.none { it.isWhitespace() }) { "credentialRef não pode conter espaços" }
            return CredentialRef(value)
        }
    }
}

enum class AccountStatus {
    AVAILABLE,
    DEGRADED,
    COOLDOWN,
    AUTHENTICATION_ERROR,
    UNAVAILABLE
}

enum class AccountFailureClass {
    SUCCESS,
    RATE_LIMIT,
    AUTH_FAILURE,
    TEMPORARY_PROVIDER_FAILURE,
    PERMANENT_PROVIDER_FAILURE,
    POLICY_DENIED,
    INVALID_REQUEST,
    TIMEOUT,
    UNKNOWN
}

data class AccountHealth(
    val status: AccountStatus = AccountStatus.AVAILABLE,
    val cooldownUntil: Instant? = null,
    val lastFailure: AccountFailureClass? = null,
    val lastFailureAt: Instant? = null
) {
    init {
        if (status == AccountStatus.COOLDOWN) require(cooldownUntil != null) { "cooldown precisa de prazo" }
        if (status != AccountStatus.COOLDOWN) require(cooldownUntil == null) { "prazo só existe durante cooldown" }
    }

    fun canSelect(now: Instant): Boolean = when (status) {
        AccountStatus.AVAILABLE, AccountStatus.DEGRADED -> true
        AccountStatus.COOLDOWN -> cooldownUntil?.let { !now.isBefore(it) } == true
        AccountStatus.AUTHENTICATION_ERROR, AccountStatus.UNAVAILABLE -> false
    }

    fun afterSuccess(now: Instant): AccountHealth = copy(
        status = AccountStatus.AVAILABLE,
        cooldownUntil = null,
        lastFailure = null,
        lastFailureAt = now
    )

    fun afterFailure(
        failure: AccountFailureClass,
        now: Instant,
        cooldown: Duration = Duration.ofSeconds(30)
    ): AccountHealth {
        require(!cooldown.isNegative) { "cooldown não pode ser negativo" }
        return when (failure) {
            AccountFailureClass.RATE_LIMIT -> copy(
                status = AccountStatus.COOLDOWN,
                cooldownUntil = now.plus(cooldown),
                lastFailure = failure,
                lastFailureAt = now
            )
            AccountFailureClass.AUTH_FAILURE -> copy(
                status = AccountStatus.AUTHENTICATION_ERROR,
                cooldownUntil = null,
                lastFailure = failure,
                lastFailureAt = now
            )
            AccountFailureClass.PERMANENT_PROVIDER_FAILURE -> copy(
                status = AccountStatus.UNAVAILABLE,
                cooldownUntil = null,
                lastFailure = failure,
                lastFailureAt = now
            )
            AccountFailureClass.TIMEOUT, AccountFailureClass.TEMPORARY_PROVIDER_FAILURE,
            AccountFailureClass.UNKNOWN -> copy(
                status = AccountStatus.DEGRADED,
                cooldownUntil = null,
                lastFailure = failure,
                lastFailureAt = now
            )
            AccountFailureClass.SUCCESS -> afterSuccess(now)
            AccountFailureClass.POLICY_DENIED, AccountFailureClass.INVALID_REQUEST -> copy(
                lastFailure = failure,
                lastFailureAt = now
            )
        }
    }
}

data class Account(
    val accountId: String,
    val providerId: String,
    val displayName: String,
    val credentialRef: CredentialRef,
    val capabilities: Set<String>,
    val priority: Int = 0,
    val health: AccountHealth = AccountHealth()
) {
    init {
        require(accountId.isNotBlank()) { "accountId é obrigatório" }
        require(providerId.isNotBlank()) { "providerId é obrigatório" }
        require(displayName.isNotBlank()) { "displayName é obrigatório" }
        require(capabilities.isNotEmpty()) { "a conta precisa declarar ao menos uma capability" }
        require(capabilities.none { it.isBlank() }) { "capability não pode ser vazia" }
    }

    fun isEligible(capability: String, now: Instant): Boolean =
        capability in capabilities && health.canSelect(now)
}

data class SelectionPolicy(
    val allowedProviders: Set<String> = emptySet(),
    val maxAttempts: Int = 1,
    val allowFallback: Boolean = false,
    val requireIdempotencyForFallback: Boolean = true
) {
    init {
        require(maxAttempts > 0) { "maxAttempts deve ser positivo" }
        require(allowedProviders.none { it.isBlank() }) { "provider permitido não pode ser vazio" }
    }

    fun allows(account: Account, capability: String, now: Instant): Boolean {
        if (!account.isEligible(capability, now)) return false
        if (allowedProviders.isNotEmpty() && account.providerId !in allowedProviders) return false
        return true
    }
}

data class AccountPool(
    val poolId: String,
    val capability: String,
    val members: List<Account>,
    val selectionPolicy: SelectionPolicy = SelectionPolicy()
) {
    init {
        require(poolId.isNotBlank()) { "poolId é obrigatório" }
        require(capability.isNotBlank()) { "capability é obrigatória" }
        require(members.map { it.accountId }.distinct().size == members.size) { "accountId duplicado no pool" }
    }

    fun candidates(
        now: Instant,
        idempotent: Boolean,
        policy: SelectionPolicy = selectionPolicy,
        authorizedAccountIds: Set<String>? = null
    ): List<Account> {
        val eligible = members
        .asSequence()
        .filter { authorizedAccountIds == null || it.accountId in authorizedAccountIds }
        .filter { policy.allows(it, capability, now) }
        .sortedWith(compareByDescending<Account> { it.priority }.thenBy { it.accountId })
        .toList()
        // A política limita a quantidade de candidatos já filtrados. Usar `members`
        // aqui permitia selecionar contas fora da capability, provider autorizado ou
        // estado saudável — e invertia o sentido de fallbackAllowed.
        val fallbackAllowed = policy.allowFallback && (!policy.requireIdempotencyForFallback || idempotent)
        return if (fallbackAllowed) eligible.take(policy.maxAttempts) else eligible.take(1)
    }

    fun select(now: Instant, idempotent: Boolean): Account? = candidates(now, idempotent).firstOrNull()
}

data class ExecutionAttempt(
    val executionId: String,
    val attemptId: String,
    val accountId: String,
    val providerId: String,
    val modelId: String?,
    val attemptNumber: Int,
    val startedAt: Instant,
    val finishedAt: Instant? = null,
    val failureClass: AccountFailureClass = AccountFailureClass.UNKNOWN
) {
    init {
        require(executionId.isNotBlank()) { "executionId é obrigatório" }
        require(attemptId.isNotBlank()) { "attemptId é obrigatório" }
        require(accountId.isNotBlank()) { "accountId é obrigatório" }
        require(providerId.isNotBlank()) { "providerId é obrigatório" }
        require(attemptNumber > 0) { "attemptNumber deve ser positivo" }
        if (finishedAt != null) require(!finishedAt.isBefore(startedAt)) { "finishedAt não pode preceder startedAt" }
    }
}
