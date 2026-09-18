package com.brain.execution

import com.brain.account.Account
import com.brain.account.AccountHealth
import com.brain.account.AccountPool
import com.brain.account.AccountRegistry
import com.brain.account.AccountRouter
import com.brain.account.CredentialRef
import com.brain.account.SelectionPolicy
import com.brain.events.InMemoryEventStore
import com.brain.planner.PassoPlano
import com.brain.planner.PlanoExecucao
import com.brain.policy.FileApprovalStore
import com.brain.policy.PolicyBroker
import com.brain.router.DefaultAIRouter
import com.brain.router.InMemoryApiCatalog
import com.brain.router.PapelPipeline
import com.brain.router.ProviderModel
import com.brain.router.RoutingProfile
import com.brain.capability.CostClass
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BrainExecutionCoordinatorAccountTest {
    @Test
    fun `coordinator falha na conta A e executa na conta B`() {
        val approvalFile = Files.createTempFile("account-approval", ".jsonl").toFile()
        try {
            val accountA = account("account-a", "provider-a", 100)
            val accountB = account("account-b", "provider-b", 90)
            val registry = RecordingRegistry(listOf(accountA, accountB))
            val pool = AccountPool(
                poolId = "coding",
                capability = "artifact.generate",
                members = listOf(accountA, accountB),
                selectionPolicy = SelectionPolicy(maxAttempts = 2, allowFallback = true)
            )
            val catalog = InMemoryApiCatalog(
                listOf(
                    model("provider-a", "a-model"),
                    model("provider-b", "b-model")
                )
            )
            val coordinator = BrainExecutionCoordinator(
                policy = PolicyBroker(listOf("artifact.generate"), mapOf("brain" to listOf("artifact.generate"))),
                events = InMemoryEventStore(),
                approvals = FileApprovalStore(approvalFile),
                router = DefaultAIRouter(),
                catalog = catalog,
                profiles = listOf(
                    RoutingProfile("provider-a", "a-model", qualityScore = 1.0),
                    RoutingProfile("provider-b", "b-model", qualityScore = 0.1)
                ),
                maxRetries = 1,
                accountRouter = AccountRouter(),
                accountPools = mapOf("artifact.generate" to pool),
                accountRegistry = registry
            )
            val usedAccounts = mutableListOf<String?>()
            val result = coordinator.execute(
                PlanoExecucao(
                    "gerar artefato",
                    listOf(PassoPlano("generate", "artifact.generate", "artefato pronto", papel = PapelPipeline.PRODUCAO_DE_ARTEFATO))
                ),
                "execution-1",
                "brain",
                executor = object : StepExecutor {
                    override fun execute(step: PassoPlano, provider: ProviderModel?): StepAttempt =
                        execute(step, provider, null)

                    override fun execute(step: PassoPlano, provider: ProviderModel?, accountId: String?): StepAttempt {
                        usedAccounts += accountId
                        return if (provider?.providerId == "provider-a") StepAttempt(false, error = "401 invalid api key")
                        else StepAttempt(true, output = "ok")
                    }
                }
            )

            assertEquals(CoordinatorStatus.COMPLETED, result.status)
            assertEquals(listOf<String?>("account-a", "account-b"), usedAccounts)
            assertEquals(com.brain.account.AccountStatus.AUTHENTICATION_ERROR, registry.find("account-a")?.health?.status)
            assertEquals("AVAILABLE", registry.find("account-b")?.health?.status?.name)
            assertTrue(registry.seenSecrets.none { it.contains("token", ignoreCase = true) })
        } finally {
            approvalFile.delete()
        }
    }

    private fun account(id: String, providerId: String, priority: Int) = Account(
        accountId = id,
        providerId = providerId,
        displayName = id,
        credentialRef = CredentialRef.of("credential:$id"),
        capabilities = setOf("artifact.generate"),
        priority = priority
    )

    private fun model(providerId: String, modelId: String) = ProviderModel(
        providerId = providerId,
        modeloId = modelId,
        papeisSugeridos = listOf(PapelPipeline.PRODUCAO_DE_ARTEFATO),
        janela = com.brain.router.JanelaLimite(),
        cost = CostClass.FREE
    )

    private class RecordingRegistry(accounts: List<Account>) : AccountRegistry {
        private val values = accounts.associateBy { it.accountId }.toMutableMap()
        val seenSecrets = mutableListOf<String>()
        override fun register(account: Account) { values[account.accountId] = account }
        override fun replace(account: Account) { values[account.accountId] = account }
        override fun remove(accountId: String): Boolean = values.remove(accountId) != null
        override fun find(accountId: String): Account? = values[accountId]
        override fun list(): List<Account> = values.values.toList()
        override fun updateHealth(accountId: String, health: AccountHealth): Account {
            val current = values.getValue(accountId)
            val updated = current.copy(health = health)
            values[accountId] = updated
            seenSecrets += updated.credentialRef.value
            return updated
        }
    }
}
