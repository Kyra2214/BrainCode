package com.brain.account

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AccountPoolContractsTest {
    private val now = Instant.parse("2026-09-17T22:00:00Z")

    private fun account(
        id: String,
        priority: Int,
        health: AccountHealth = AccountHealth(),
        provider: String = "provider-a"
    ) = Account(
        accountId = id,
        providerId = provider,
        displayName = id,
        credentialRef = CredentialRef.of("credential:$id"),
        capabilities = setOf("prompt.generate"),
        priority = priority,
        health = health
    )

    @Test
    fun `credential ref aceita somente referencia opaca`() {
        assertFailsWith<IllegalArgumentException> { CredentialRef.of("sk-live-secret-token") }
        assertFailsWith<IllegalArgumentException> { CredentialRef.of("credential:com espaço") }
    }

    @Test
    fun `account health coloca rate limit em cooldown`() {
        val health = AccountHealth().afterFailure(AccountFailureClass.RATE_LIMIT, now, Duration.ofMinutes(2))

        assertEquals(AccountStatus.COOLDOWN, health.status)
        assertEquals(now.plusSeconds(120), health.cooldownUntil)
        assertFalse(health.canSelect(now.plusSeconds(119)))
        assertTrue(health.canSelect(now.plusSeconds(120)))
    }

    @Test
    fun `auth failure e falha permanente nao permitem selecao`() {
        assertFalse(account("auth", 10, AccountHealth().afterFailure(AccountFailureClass.AUTH_FAILURE, now)).isEligible("prompt.generate", now))
        assertFalse(account("perm", 10, AccountHealth().afterFailure(AccountFailureClass.PERMANENT_PROVIDER_FAILURE, now)).isEligible("prompt.generate", now))
        assertTrue(account("ok", 10).isEligible("prompt.generate", now))
    }

    @Test
    fun `pool seleciona por prioridade e exclui cooldown`() {
        val cooldown = AccountHealth().afterFailure(AccountFailureClass.RATE_LIMIT, now, Duration.ofMinutes(5))
        val pool = AccountPool(
            poolId = "prompts",
            capability = "prompt.generate",
            members = listOf(account("low", 10), account("blocked", 100, cooldown), account("high", 50)),
            selectionPolicy = SelectionPolicy(maxAttempts = 2, allowFallback = true)
        )

        assertEquals(listOf("high", "low"), pool.candidates(now, idempotent = true).map { it.accountId })
        assertEquals("high", pool.select(now, idempotent = true)?.accountId)
    }

    @Test
    fun `provider permitido e capability limitam candidatos`() {
        val pool = AccountPool(
            poolId = "prompts",
            capability = "prompt.generate",
            members = listOf(account("a", 100, provider = "provider-a"), account("b", 90, provider = "provider-b")),
            selectionPolicy = SelectionPolicy(allowedProviders = setOf("provider-b"), maxAttempts = 2, allowFallback = true)
        )

        assertEquals(listOf("b"), pool.candidates(now, idempotent = true).map { it.accountId })
        assertNull(pool.select(now, idempotent = true)?.takeIf { it.providerId == "provider-a" })
    }

    @Test
    fun `operacao nao idempotente permite primeira tentativa mas bloqueia fallback`() {
        val pool = AccountPool(
            poolId = "external-write",
            capability = "external.write",
            members = listOf(
                account("a", 100).copy(capabilities = setOf("external.write")),
                account("b", 90).copy(capabilities = setOf("external.write"))
            ),
            selectionPolicy = SelectionPolicy(maxAttempts = 3, allowFallback = true, requireIdempotencyForFallback = true)
        )

        assertEquals(listOf("a"), pool.candidates(now, idempotent = false).map { it.accountId })
        assertEquals(listOf("a", "b"), pool.candidates(now, idempotent = true).map { it.accountId })
    }

    @Test
    fun `execution attempt nao aceita tempo invertido nem contador invalido`() {
        assertFailsWith<IllegalArgumentException> {
            ExecutionAttempt("exec", "attempt", "a", "provider", "model", 0, now)
        }
        assertFailsWith<IllegalArgumentException> {
            ExecutionAttempt("exec", "attempt", "a", "provider", "model", 1, now, now.minusSeconds(1))
        }
    }
}
