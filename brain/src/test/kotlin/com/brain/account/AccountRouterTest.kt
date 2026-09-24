package com.brain.account

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class AccountRouterTest {
    private val now = Instant.parse("2026-09-17T22:00:00Z")

    private fun account(id: String, priority: Int, health: AccountHealth = AccountHealth()) = Account(
        accountId = id,
        providerId = "provider-$id",
        displayName = id,
        credentialRef = CredentialRef.of("credential:$id"),
        capabilities = setOf("prompt.generate"),
        priority = priority,
        health = health
    )

    private fun request(pool: AccountPool, authorized: Set<String>) = AccountRouteRequest(
        executionId = "exec-1",
        capability = "prompt.generate",
        pool = pool,
        authorizedAccountIds = authorized,
        idempotent = true,
        requestedModelId = "model-1"
    )

    @Test
    fun `router escolhe maior prioridade entre contas autorizadas`() {
        val pool = AccountPool("pool", "prompt.generate", listOf(account("low", 10), account("high", 100)))
        val decision = AccountRouter().route(request(pool, setOf("low", "high")), now)

        val selected = assertIs<AccountRouteDecision.Selected>(decision)
        assertEquals("high", selected.accountId)
        assertEquals("provider-high", selected.providerId)
        assertEquals("model-1", selected.requestedModelId)
    }

    @Test
    fun `router nunca escolhe conta fora da autorizacao`() {
        val pool = AccountPool("pool", "prompt.generate", listOf(account("high", 100), account("low", 10)))
        val decision = AccountRouter().route(request(pool, setOf("low")), now)

        assertEquals("low", assertIs<AccountRouteDecision.Selected>(decision).accountId)
    }

    @Test
    fun `router exclui cooldown e retorna indisponivel sem conta autorizada saudavel`() {
        val cooldown = AccountHealth().afterFailure(AccountFailureClass.RATE_LIMIT, now, Duration.ofMinutes(5))
        val pool = AccountPool("pool", "prompt.generate", listOf(account("blocked", 100, cooldown)))
        val decision = AccountRouter().route(request(pool, setOf("blocked")), now)

        val unavailable = assertIs<AccountRouteDecision.Unavailable>(decision)
        assertEquals("exec-1", unavailable.executionId)
        assertEquals(listOf("NO_HEALTHY_ACCOUNT", "NO_ELIGIBLE_ACCOUNT"), unavailable.reasonCodes)
    }

    @Test
    fun `router nao transforma pool vazio em selecao arbitraria`() {
        val pool = AccountPool("pool", "prompt.generate", emptyList())
        val decision = AccountRouter().route(request(pool, emptySet()), now)

        val unavailable = assertIs<AccountRouteDecision.Unavailable>(decision)
        assertEquals(listOf("POOL_EMPTY", "CAPABILITY_UNAVAILABLE", "NO_ELIGIBLE_ACCOUNT"), unavailable.reasonCodes)
    }
}
