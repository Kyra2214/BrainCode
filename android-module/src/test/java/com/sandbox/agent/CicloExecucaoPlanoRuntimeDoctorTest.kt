package com.sandbox.agent

import com.brain.capability.CapabilityAvailability
import com.brain.capability.CapabilityCategory
import com.brain.capability.CapabilityDefinition
import com.brain.capability.CapabilityDiscovery
import com.brain.capability.CapabilityProvenance
import com.brain.capability.CapabilityRegistry
import com.brain.dispatch.Dispatcher
import com.brain.events.InMemoryEventStore
import com.brain.gateway.ActionExecution
import com.brain.gateway.ActionGateway
import com.brain.gateway.InMemoryActionAuditLog
import com.brain.planner.PassoPlano
import com.brain.planner.PlanoExecucao
import com.brain.policy.PolicyBroker
import com.brain.router.DefaultAIRouter
import com.brain.router.InMemoryApiCatalog
import com.brain.runtime.RuntimeDiagnosis
import com.brain.runtime.RuntimeDoctor
import com.brain.runtime.RuntimeDoctorImpl
import com.sandbox.runtime.FileExecutionLogRepository
import com.sandbox.runtime.ManagedSandboxRuntime
import com.sandbox.runtime.SandboxProcessLauncher
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Item 7: o doutor real é chamado no caminho Dispatcher/Gateway e nunca derruba o ciclo. */
class CicloExecucaoPlanoRuntimeDoctorTest {
    private val definition = CapabilityDefinition(
        id = "broken.step", name = "broken.step", description = "sempre falha",
        category = CapabilityCategory.TOOL, ownerId = "tool.owner", origin = "builtin",
        providedCapabilities = setOf("broken.step"), availability = CapabilityAvailability.AVAILABLE,
        provenance = listOf(CapabilityProvenance("test", "unit-test"))
    )

    private class TestLauncher(private val rootDir: File) : SandboxProcessLauncher {
        override fun launch(command: List<String>, workingDir: String): Process =
            ProcessBuilder(command).directory(File(rootDir, workingDir.removePrefix("/")).apply { mkdirs() }).start()
    }

    private fun ciclo(root: File, doctor: RuntimeDoctor): CicloExecucaoPlano {
        val registry = CapabilityRegistry(listOf(definition))
        val policy = PolicyBroker(actorCapabilities = mapOf("agent-1" to listOf("broken.step"))).withCapabilityRegistry(registry)
        val gateway = ActionGateway(registry, policy, { _, _, _ -> ActionExecution(false, error = "falha definitiva") }, InMemoryActionAuditLog())
        val runtime = ManagedSandboxRuntime(TestLauncher(root), FileExecutionLogRepository(File(root, "logs")), sessionId = "s")
        return CicloExecucaoPlano(
            policyBroker = policy,
            sandbox = Sandbox(runtime = runtime, rootfsDir = root, capabilityResolver = CapabilityResolver()),
            router = DefaultAIRouter(),
            catalog = InMemoryApiCatalog(emptyList()),
            dispatcher = Dispatcher(CapabilityDiscovery(registry), gateway),
            runtimeDoctor = doctor,
            sleeper = {}
        )
    }

    private val plano = PlanoExecucao("falhar", listOf(PassoPlano("passo-1", "broken.step", "nunca")))

    @Test
    fun `falha de passo aciona diagnose com o erro do gateway e grava evento no EventStore`() {
        val root = Files.createTempDirectory("ciclo-doctor-").toFile()
        try {
            val events = InMemoryEventStore()
            val resultado = ciclo(root, RuntimeDoctorImpl(events)).autorizarEExecutar(plano, "run-doctor", "agent-1")

            assertEquals(StatusPasso.REPROVADO, resultado.passos.single().status)
            val diagnostic = events.replay("run-doctor").single { it.type == "runtime.diagnostic" }
            assertEquals("passo-1", diagnostic.taskId)
        } finally { root.deleteRecursively() }
    }

    @Test
    fun `excecao do doutor nao propaga e o passo continua reprovado normalmente`() {
        val root = Files.createTempDirectory("ciclo-doctor-throw-").toFile()
        try {
            var chamadas = 0
            val doctor = object : RuntimeDoctor {
                override fun diagnose(runId: String, taskId: String, error: String?): RuntimeDiagnosis {
                    chamadas++
                    error("event store indisponível")
                }
                override fun repair(runId: String, taskId: String, diagnosis: RuntimeDiagnosis) = false
            }
            val resultado = ciclo(root, doctor).autorizarEExecutar(plano, "run-doctor-throw", "agent-1")

            assertEquals(1, chamadas)
            assertEquals(StatusPasso.REPROVADO, resultado.passos.single().status)
            assertTrue(!resultado.aprovado)
        } finally { root.deleteRecursively() }
    }
}
