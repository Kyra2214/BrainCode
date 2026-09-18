package com.brain.account

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.time.Instant

class AccountRegistryTest {
    private fun account(id: String, priority: Int) = Account(
        accountId = id,
        providerId = "provider",
        displayName = id,
        credentialRef = CredentialRef.of("credential:$id"),
        capabilities = setOf("prompt.generate"),
        priority = priority
    )

    @Test
    fun `registry registra ordena encontra atualiza e remove`() {
        val registry = InMemoryAccountRegistry()
        registry.register(account("low", 10))
        registry.register(account("high", 100))

        assertEquals(listOf("high", "low"), registry.list().map { it.accountId })
        assertEquals("low", registry.find("low")?.accountId)

        val cooldown = AccountHealth().afterFailure(
            AccountFailureClass.RATE_LIMIT,
            Instant.parse("2026-09-17T22:00:00Z")
        )
        val updated = registry.updateHealth("low", cooldown)
        assertEquals(AccountStatus.COOLDOWN, updated.health.status)
        assertEquals(AccountStatus.COOLDOWN, registry.find("low")?.health?.status)

        assertTrue(registry.remove("low"))
        assertFalse(registry.remove("low"))
        assertNull(registry.find("low"))
    }

    @Test
    fun `registry rejeita duplicidade e replace de inexistente`() {
        val registry = InMemoryAccountRegistry()
        registry.register(account("one", 1))

        assertFailsWith<IllegalStateException> { registry.register(account("one", 2)) }
        assertFailsWith<IllegalStateException> { registry.replace(account("missing", 2)) }
    }

    @Test
    fun `replace atualiza sem criar segunda conta`() {
        val registry = InMemoryAccountRegistry()
        registry.register(account("one", 1))
        registry.replace(account("one", 99))

        assertEquals(1, registry.list().size)
        assertEquals(99, registry.find("one")?.priority)
    }
}
