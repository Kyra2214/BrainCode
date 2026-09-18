package com.brain.account

import java.util.concurrent.ConcurrentHashMap

/** Fonte única de contas lógicas; não lê nem persiste credenciais reais. */
interface AccountRegistry {
    fun register(account: Account)
    fun replace(account: Account)
    fun remove(accountId: String): Boolean
    fun find(accountId: String): Account?
    fun list(): List<Account>
    fun updateHealth(accountId: String, health: AccountHealth): Account
}

/** Implementação determinística para runtime local e testes; persistência fica para item posterior. */
class InMemoryAccountRegistry : AccountRegistry {
    private val accounts = ConcurrentHashMap<String, Account>()

    override fun register(account: Account) {
        check(accounts.putIfAbsent(account.accountId, account) == null) {
            "accountId já registrado: ${account.accountId}"
        }
    }

    override fun replace(account: Account) {
        check(accounts.replace(account.accountId, account) != null) {
            "accountId não registrado: ${account.accountId}"
        }
    }

    override fun remove(accountId: String): Boolean = accounts.remove(accountId) != null

    override fun find(accountId: String): Account? = accounts[accountId]

    override fun list(): List<Account> = accounts.values.sortedWith(
        compareByDescending<Account> { it.priority }.thenBy { it.accountId }
    )

    override fun updateHealth(accountId: String, health: AccountHealth): Account {
        var updated: Account? = null
        accounts.compute(accountId) { _, current ->
            checkNotNull(current) { "accountId não registrado: $accountId" }
            current.copy(health = health).also { updated = it }
        }
        return checkNotNull(updated)
    }
}
