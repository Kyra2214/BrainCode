package com.brain.account

import com.brain.provider.*
import org.junit.Assert.assertEquals
import org.junit.Test

class ProviderAccountRouterTest {
    private val client = object : ProviderClient {
        override fun complete(request: ProviderRequest): Result<ProviderResponse> = Result.success(ProviderResponse(200, "ok", 1, "provider-a"))
    }

    @Test fun `roteador usa somente provider registrado e conta autorizada`() {
        val providers = InMemoryProviderRegistry().also { it.register(ProviderRegistration("provider-a", "A", client, setOf("coding"))) }
        val accounts = InMemoryAccountRegistry().also { it.register(Account("account-a", "provider-a", "A", CredentialRef.of("credential:account-a"), setOf("coding"), priority = 10)) }
        val pool = AccountPool("coding", "coding", accounts.list())
        val decision = ProviderAccountRouter(providers, accounts).route(
            ProviderAccountRouteRequest("run", "coding", pool, setOf("account-a"), idempotent = true)
        )
        assertEquals("account-a", (decision as AccountRouteDecision.Selected).accountId)
    }

    @Test fun `roteador rejeita provider não registrado`() {
        val providers = InMemoryProviderRegistry()
        val accounts = InMemoryAccountRegistry().also { it.register(Account("account-a", "missing", "A", CredentialRef.of("credential:account-a"), setOf("coding"))) }
        val decision = ProviderAccountRouter(providers, accounts).route(
            ProviderAccountRouteRequest("run", "coding", AccountPool("coding", "coding", accounts.list()), setOf("account-a"), true)
        )
        assertEquals(AccountRouteDecision.Unavailable::class, decision::class)
    }
}
