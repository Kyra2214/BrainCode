package com.sandbox.agent

import com.brain.account.Account
import com.brain.account.AccountHealth
import com.brain.account.AccountPool
import com.brain.account.AccountRegistry
import com.brain.account.AccountRouter
import com.brain.account.AccountStatus
import com.brain.account.CredentialRef
import com.brain.account.SelectionPolicy
import com.brain.capability.CapabilityAvailability
import com.brain.capability.CapabilityCategory
import com.brain.capability.CapabilityDefinition
import com.brain.capability.CapabilityDiscovery
import com.brain.capability.CapabilityProvenance
import com.brain.capability.CapabilityRegistry
import com.brain.capability.CostClass
import com.brain.dispatch.Dispatcher
import com.brain.gateway.ActionExecution
import com.brain.gateway.ActionGateway
import com.brain.gateway.InMemoryActionAuditLog
import com.brain.planner.PassoPlano
import com.brain.planner.PlanoExecucao
import com.brain.policy.PolicyBroker
import com.brain.router.DefaultAIRouter
import com.brain.router.InMemoryApiCatalog
import com.brain.router.JanelaLimite
import com.brain.router.PapelPipeline
import com.brain.router.ProviderModel
import com.brain.router.RoutingProfile
import com.sandbox.runtime.FileExecutionLogRepository
import com.sandbox.runtime.ManagedSandboxRuntime
import com.sandbox.runtime.SandboxProcessLauncher
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fase 2 (ver docs/LEGADO_E_DECISOES.md): fecha, no caminho de produção real
 * (CicloExecucaoPlano -> Dispatcher -> ActionGateway), o item de backlog que antes só
 * existia em BrainExecutionCoordinator (deprecated, sem chamador): fallback entre contas
 * da mesma capability, com a saúde da conta atualizada no AccountRegistry.
 */
class CicloExecucaoPlanoAccountFallbackTest {

    private fun definition(id: String, provides: Set<String>) = CapabilityDefinition(
        id = id,
        name = id,
        description = "capability de teste",
        category = CapabilityCategory.TOOL,
        ownerId = "tool.owner",
        origin = "builtin",
        providedCapabilities = provides,
        availability = CapabilityAvailability.AVAILABLE,
        provenance = listOf(CapabilityProvenance("test", "unit-test"))
    )

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
        janela = JanelaLimite(),
        cost = CostClass.FREE
    )

    private fun sandbox(root: File): Sandbox {
        val runtime = ManagedSandboxRuntime(TestLauncher(root), FileExecutionLogRepository(File(root, "logs")), sessionId = "session-1")
        return Sandbox(runtime = runtime, rootfsDir = root)
    }

    private class InMemoryAccountRegistryForTest(accounts: List<Account>) : AccountRegistry {
        private val values = accounts.associateBy { it.accountId }.toMutableMap()
        override fun register(account: Account) { values[account.accountId] = account }
        override fun replace(account: Account) { values[account.accountId] = account }
        override fun remove(accountId: String): Boolean = values.remove(accountId) != null
        override fun find(accountId: String): Account? = values[accountId]
        override fun list(): List<Account> = values.values.toList()
        override fun updateHealth(accountId: String, health: AccountHealth): Account {
            val updated = values.getValue(accountId).copy(health = health)
            values[accountId] = updated
            return updated
        }
    }

    @Test
    fun `dispatcher falha na conta A, cai para a conta B e a saude das duas contas fica registrada`() {
        val root = Files.createTempDirectory("ciclo-account-fallback-").toFile()
        try {
            val accountA = account("account-a", "provider-a", priority = 100)
            val accountB = account("account-b", "provider-b", priority = 90)
            val registry = InMemoryAccountRegistryForTest(listOf(accountA, accountB))
            val pool = AccountPool(
                poolId = "artefatos",
                capability = "artifact.generate",
                members = listOf(accountA, accountB),
                selectionPolicy = SelectionPolicy(maxAttempts = 2, allowFallback = true)
            )

            val capRegistry = CapabilityRegistry(listOf(definition("artifact.generate", setOf("artifact.generate"))))
            val policy = PolicyBroker(
                allowedCapabilities = listOf("artifact.generate"),
                actorCapabilities = mapOf("agent-1" to listOf("artifact.generate"))
            ).withCapabilityRegistry(capRegistry)
            val audit = InMemoryActionAuditLog()
            val contasTentadas = mutableListOf<String?>()
            val gateway = ActionGateway(capRegistry, policy, { request, _, _ ->
                contasTentadas += request.accountId
                if (request.accountId == "account-a") ActionExecution(false, error = "401 invalid api key")
                else ActionExecution(true, "ok")
            }, audit)
            val dispatcher = Dispatcher(CapabilityDiscovery(capRegistry), gateway)

            val ciclo = CicloExecucaoPlano(
                policyBroker = policy,
                sandbox = sandbox(root),
                router = DefaultAIRouter(),
                catalog = InMemoryApiCatalog(listOf(model("provider-a", "a-model"), model("provider-b", "b-model"))),
                profiles = listOf(
                    RoutingProfile("provider-a", "a-model", qualityScore = 1.0),
                    RoutingProfile("provider-b", "b-model", qualityScore = 0.1)
                ),
                dispatcher = dispatcher,
                accountRouter = AccountRouter(),
                accountPools = mapOf("artifact.generate" to pool),
                authorizedAccountIds = setOf("account-a", "account-b"),
                accountRegistry = registry,
                sleeper = {}
            )
            val plano = PlanoExecucao(
                "gerar artefato",
                listOf(PassoPlano("generate", "artifact.generate", "artefato pronto", papel = PapelPipeline.PRODUCAO_DE_ARTEFATO))
            )

            val resultado = ciclo.autorizarEExecutar(plano, "run-account-fallback", "agent-1")

            assertTrue(resultado.aprovado)
            assertEquals(listOf<String?>("account-a", "account-b"), contasTentadas)
            assertEquals(AccountStatus.AUTHENTICATION_ERROR, registry.find("account-a")?.health?.status)
            assertEquals(AccountStatus.AVAILABLE, registry.find("account-b")?.health?.status)
        } finally {
            root.deleteRecursively()
        }
    }

    private class TestLauncher(private val rootDir: File) : SandboxProcessLauncher {
        override fun launch(command: List<String>, workingDir: String): Process {
            val hostDir = File(rootDir, workingDir.removePrefix("/")).apply { mkdirs() }
            return ProcessBuilder(command).directory(hostDir).start()
        }
    }
}
