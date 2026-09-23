package com.sandbox.agent

import com.brain.capability.CapabilityAvailability
import com.brain.capability.CapabilityCategory
import com.brain.capability.CapabilityDefinition
import com.brain.capability.CapabilityDiscovery
import com.brain.capability.CapabilityProvenance
import com.brain.capability.CapabilityRegistry
import com.brain.dispatch.Dispatcher
import com.brain.gateway.ActionExecution
import com.brain.gateway.ActionGateway
import com.brain.gateway.InMemoryActionAuditLog
import com.brain.memory.InMemoryExperienceMemory
import com.brain.memory.ResultadoExperiencia
import com.brain.planner.PassoPlano
import com.brain.planner.PlanoExecucao
import com.brain.policy.PolicyBroker
import com.brain.router.DefaultAIRouter
import com.brain.router.InMemoryApiCatalog
import com.sandbox.runtime.FileExecutionLogRepository
import com.sandbox.runtime.ManagedSandboxRuntime
import com.sandbox.runtime.SandboxProcessLauncher
import java.io.File
import java.nio.file.Files
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fase 2 (ver docs/LEGADO_E_DECISOES.md): cobre, no caminho de produção real
 * (CicloExecucaoPlano -> Dispatcher -> ActionGateway), as capacidades que
 * antes só existiam em BrainExecutionCoordinator (deprecated, sem chamador):
 * retry com backoff para falha transitória e gravação em ExperienceMemory.
 */
class CicloExecucaoPlanoDispatcherTest {

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

    private fun sandbox(root: File): Sandbox {
        val runtime = ManagedSandboxRuntime(TestLauncher(root), FileExecutionLogRepository(File(root, "logs")), sessionId = "session-1")
        val fakeCatalog = CapabilityResolver(mapOf(
            "sandbox.hello" to { params: List<String> -> CapabilityResolver.Resolution.Comando(listOf("echo", "ok") + params) }
        ))
        return Sandbox(runtime = runtime, rootfsDir = root, capabilityResolver = fakeCatalog)
    }

    private fun <T> await(block: suspend () -> T): T {
        var completed: Result<T>? = null
        block.startCoroutine(object : Continuation<T> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(result: Result<T>) { completed = result }
        })
        return completed!!.getOrThrow()
    }

    @Test
    fun `passo transitoriamente instavel e reexecutado, aprovado e memoria registra correcao`() {
        val root = Files.createTempDirectory("ciclo-retry-").toFile()
        try {
            var chamadas = 0
            val registry = CapabilityRegistry(listOf(definition("flaky.step", setOf("flaky.step"))))
            val policy = PolicyBroker(actorCapabilities = mapOf("agent-1" to listOf("flaky.step")))
                .withCapabilityRegistry(registry)
            val audit = InMemoryActionAuditLog()
            val gateway = ActionGateway(registry, policy, { _, _, _ ->
                chamadas++
                if (chamadas == 1) ActionExecution(false, error = "instabilidade transitória", retryable = true)
                else ActionExecution(true, "ok")
            }, audit)
            val dispatcher = Dispatcher(CapabilityDiscovery(registry), gateway)
            val memory = InMemoryExperienceMemory()
            val ciclo = CicloExecucaoPlano(
                policyBroker = policy,
                sandbox = sandbox(root),
                router = DefaultAIRouter(),
                catalog = InMemoryApiCatalog(emptyList()),
                dispatcher = dispatcher,
                memory = memory,
                sleeper = {}
            )
            val plano = PlanoExecucao("estabilizar", listOf(PassoPlano("passo-1", "flaky.step", "concluiu sem erro")))

            val resultado = ciclo.autorizarEExecutar(plano, "run-retry", "agent-1")

            assertTrue(resultado.aprovado)
            assertEquals(2, chamadas)
            val experiencia = await { memory.buscarPorTarefa("passo-1") }.single()
            assertEquals(ResultadoExperiencia.CORRIGIDO_APOS_FALHA, experiencia.resultado)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `passo idempotente esgota tentativas e reprova sem lancar excecao, memoria registra falha`() {
        val root = Files.createTempDirectory("ciclo-retry-fail-").toFile()
        try {
            var chamadas = 0
            val registry = CapabilityRegistry(listOf(definition("flaky.step", setOf("flaky.step"))))
            val policy = PolicyBroker(actorCapabilities = mapOf("agent-1" to listOf("flaky.step")))
                .withCapabilityRegistry(registry)
            val audit = InMemoryActionAuditLog()
            val gateway = ActionGateway(registry, policy, { _, _, _ ->
                chamadas++
                ActionExecution(false, error = "sempre instável", retryable = true)
            }, audit)
            val dispatcher = Dispatcher(CapabilityDiscovery(registry), gateway)
            val memory = InMemoryExperienceMemory()
            val ciclo = CicloExecucaoPlano(
                policyBroker = policy,
                sandbox = sandbox(root),
                router = DefaultAIRouter(),
                catalog = InMemoryApiCatalog(emptyList()),
                dispatcher = dispatcher,
                memory = memory,
                maxRetries = 1,
                sleeper = {}
            )
            val plano = PlanoExecucao("estabilizar", listOf(PassoPlano("passo-1", "flaky.step", "concluiu sem erro")))

            val resultado = ciclo.autorizarEExecutar(plano, "run-retry-fail", "agent-1")

            assertTrue(!resultado.aprovado)
            assertEquals(StatusPasso.REPROVADO, resultado.passos.single().status)
            assertEquals(2, chamadas)
            val experiencia = await { memory.buscarPorTarefa("passo-1") }.single()
            assertEquals(ResultadoExperiencia.FALHA, experiencia.resultado)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `passo nao idempotente com falha transitoria nao e reexecutado`() {
        val root = Files.createTempDirectory("ciclo-retry-nonidempotent-").toFile()
        try {
            var chamadas = 0
            val registry = CapabilityRegistry(listOf(definition("flaky.step", setOf("flaky.step"))))
            val policy = PolicyBroker(actorCapabilities = mapOf("agent-1" to listOf("flaky.step")))
                .withCapabilityRegistry(registry)
            val audit = InMemoryActionAuditLog()
            val gateway = ActionGateway(registry, policy, { _, _, _ ->
                chamadas++
                ActionExecution(false, error = "instabilidade transitória", retryable = true)
            }, audit)
            val dispatcher = Dispatcher(CapabilityDiscovery(registry), gateway)
            val ciclo = CicloExecucaoPlano(
                policyBroker = policy,
                sandbox = sandbox(root),
                router = DefaultAIRouter(),
                catalog = InMemoryApiCatalog(emptyList()),
                dispatcher = dispatcher,
                sleeper = {}
            )
            val plano = PlanoExecucao(
                "acao unica",
                listOf(PassoPlano("passo-1", "flaky.step", "concluiu sem erro", idempotent = false, idempotencyKey = "run-single-attempt:passo-1"))
            )

            val resultado = ciclo.autorizarEExecutar(plano, "run-single-attempt", "agent-1")

            assertTrue(!resultado.aprovado)
            assertEquals(1, chamadas)
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
