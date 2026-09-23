package com.sandbox.agent

import com.brain.account.AccountRouter
import com.brain.capability.CapabilityAvailability
import com.brain.capability.CapabilityCategory
import com.brain.capability.CapabilityDefinition
import com.brain.capability.CapabilityDiscovery
import com.brain.capability.CapabilityProvenance
import com.brain.capability.CapabilityRegistry
import com.brain.dispatch.Dispatcher
import com.brain.gateway.ActionExecution
import com.brain.gateway.ActionExecutor
import com.brain.gateway.ActionGateway
import com.brain.gateway.ActionRequest
import com.brain.gateway.InMemoryActionAuditLog
import com.brain.planner.PassoPlano
import com.brain.planner.PlanoExecucao
import com.brain.policy.PolicyBroker
import com.brain.policy.PolicyDecision
import com.brain.prompt.PromptCreatorAgent
import com.brain.prompt.PromptCriado
import com.brain.prompt.PromptDomain
import com.brain.prompt.PromptLibrary
import com.brain.prompt.PromptTemplate
import com.brain.router.DefaultAIRouter
import com.brain.router.InMemoryApiCatalog
import com.brain.secretary.DeterministicSecretary
import com.brain.secretary.Door
import com.sandbox.app.PromptGenerationExecutor
import com.sandbox.app.PromptImprover
import com.sandbox.runtime.FileExecutionLogRepository
import com.sandbox.runtime.ManagedSandboxRuntime
import com.sandbox.runtime.SandboxProcessLauncher
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regressão do bug corrigido em 23/09/2026 (ver
 * docs/auditoria/PLANO_CORRECAO_AUDITORIA_ESCALONAMENTO.md, item 1): antes, `authorizedAccountIds`
 * chegava sempre vazio ao `PromptGenerationExecutor` para um PRIMEIRO pedido na Porta 2 ("crie um
 * prompt de X"), sem nenhuma palavra de melhoria — porque `DoorScope.externalAccountsAllowed` só
 * era verdadeiro quando `DeterministicSecretary.classify()` já via um gatilho de melhoria no texto
 * original, calculado antes de qualquer prompt existir ou ser validado. Este teste cobre o caminho
 * real de produção (Secretário -> CicloExecucaoPlano -> Dispatcher -> ActionGateway -> executor),
 * não só a unidade `DoorPolicy`.
 */
class PromptDoorAccountVisibilityTest {

    private class FakePromptLibrary : PromptLibrary {
        private val templates = ConcurrentHashMap<String, PromptTemplate>()
        override suspend fun buscarPorContexto(contextoDeUso: String): List<PromptTemplate> = templates.values.toList()
        override suspend fun salvarNovaVersao(template: PromptTemplate): String { templates[template.id] = template; return template.id }
        override suspend fun registrarResultado(templateId: String, sucesso: Boolean, custo: Double, tempoMs: Long) = Unit
        override suspend fun aposentar(templateId: String): Boolean = templates.remove(templateId) != null
    }

    /** Sempre produz um texto fraco, garantindo scoreInicial.abaixoDoPadrao = true no executor. */
    private val criadorFraco = object : PromptCreatorAgent {
        override fun criar(pedido: String, contexto: PromptTemplate?, contextoPesquisa: String?): PromptCriado =
            PromptCriado("xícara", PromptDomain.IMAGEM, "test:fraco")
        override fun melhorarLocalmente(promptAtual: String, pedidoOriginal: String, pontosFracos: Set<String>, contextoPesquisa: String?): PromptCriado =
            PromptCriado(promptAtual, PromptDomain.IMAGEM, "test:fraco")
    }

    private fun sandbox(root: File): Sandbox {
        val runtime = ManagedSandboxRuntime(TestLauncher(root), FileExecutionLogRepository(File(root, "logs")), sessionId = "session-1")
        return Sandbox(runtime = runtime, rootfsDir = root)
    }

    private class TestLauncher(private val rootDir: File) : SandboxProcessLauncher {
        override fun launch(command: List<String>, workingDir: String): Process {
            val hostDir = File(rootDir, workingDir.removePrefix("/")).apply { mkdirs() }
            return ProcessBuilder(command).directory(hostDir).start()
        }
    }

    @Test
    fun `primeiro pedido de prompt sem gatilho de melhoria chega com conta autorizada ao executor da Porta 2`() {
        val root = Files.createTempDirectory("prompt-door-account-visibility-").toFile()
        try {
            val objetivo = "crie um prompt de uma xícara de café"

            // Sanidade: o pedido não contém nenhuma palavra de melhoria — é exatamente o caso que
            // o bug do item 1 deixava sem conta externa.
            val doorScope = DeterministicSecretary().classify(objetivo).scope
            assertEquals(Door.PROMPT, doorScope.door)
            assertTrue("Porta 2 deveria liberar contas visíveis desde a classificação", doorScope.externalAccountsAllowed)

            val capability = CapabilityDefinition(
                id = "prompt.library.generate", name = "prompt.library.generate", description = "teste",
                category = CapabilityCategory.SANDBOX, ownerId = "test", origin = "test",
                providedCapabilities = setOf("prompt.library.write"),
                availability = CapabilityAvailability.AVAILABLE, provenance = listOf(CapabilityProvenance("test", "test"))
            )
            val capRegistry = CapabilityRegistry(listOf(capability))
            val policy = PolicyBroker(
                allowedCapabilities = listOf("prompt.library.generate"),
                actorCapabilities = mapOf("agent-1" to listOf("prompt.library.generate"))
            ).withCapabilityRegistry(capRegistry)

            val authorizedAccountIdsRecebidos = mutableListOf<Set<String>>()
            val iaComContaExigida = object : PromptImprover {
                override fun melhorar(promptAtual: String, pedidoOriginal: String): String =
                    "Fotografia profissional detalhada de uma xícara de café: $promptAtual Composição balanceada, iluminação de estúdio, alta definição."
                override fun requerContaAutorizada(): Boolean = true
            }
            val promptExecutor = PromptGenerationExecutor(
                promptLibrary = FakePromptLibrary(),
                improver = iaComContaExigida,
                creator = criadorFraco
            )
            val executorCapturando = ActionExecutor { request, cap, decision ->
                authorizedAccountIdsRecebidos += decision.authorizedAccountIds
                promptExecutor.execute(request, cap, decision)
            }

            val gateway = ActionGateway(capRegistry, policy, executorCapturando, InMemoryActionAuditLog())
            val dispatcher = Dispatcher(CapabilityDiscovery(capRegistry), gateway)

            val ciclo = CicloExecucaoPlano(
                policyBroker = policy,
                sandbox = sandbox(root),
                router = DefaultAIRouter(),
                catalog = InMemoryApiCatalog(emptyList()),
                dispatcher = dispatcher,
                accountRouter = AccountRouter(),
                authorizedAccountIds = setOf("acct-1"),
                sleeper = {}
            )
            val plano = PlanoExecucao(
                objetivo,
                listOf(PassoPlano("gerar-prompt", "prompt.library.generate", "prompt gerado", parametros = listOf(objetivo)))
            )

            val resultado = ciclo.autorizarEExecutar(plano, "run-prompt-door-visibility", "agent-1", doorScope)

            assertTrue("o passo deveria ter sido aprovado", resultado.aprovado)
            assertEquals(1, authorizedAccountIdsRecebidos.size)
            assertEquals(
                "authorizedAccountIds deveria chegar não-vazio ao executor mesmo sem gatilho de melhoria",
                setOf("acct-1"),
                authorizedAccountIdsRecebidos.single()
            )
            val decisaoFinal: PolicyDecision? = resultado.passos.single().decisaoPolicy
            assertEquals(setOf("acct-1"), decisaoFinal?.authorizedAccountIds)
        } finally {
            root.deleteRecursively()
        }
    }
}
