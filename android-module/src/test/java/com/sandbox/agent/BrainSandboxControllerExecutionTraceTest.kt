package com.sandbox.agent

import com.brain.gateway.ActionExecution
import com.brain.gateway.ActionExecutor
import com.brain.observability.TraceStage
import com.brain.secretary.DeterministicSecretary
import com.sandbox.runtime.FileExecutionLogRepository
import com.sandbox.runtime.ManagedSandboxRuntime
import com.sandbox.runtime.SandboxProcessLauncher
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fase 12 (ver PLANO_CONEXAO_FASE_12.md, seção 1): comprova que o ActionGateway *real*
 * instanciado por BrainSandboxController (não um ActionGateway construído à parte no
 * teste) produz eventos de trace recuperáveis pelo EventStore para um ciclo completo.
 */
class BrainSandboxControllerExecutionTraceTest {
    @Test
    fun `ciclo real via BrainSandboxController produz trace para TASK, CAPABILITY, POLICY e EVIDENCE`() {
        val root = Files.createTempDirectory("trace-wiring-").toFile()
        try {
            val controller = BrainSandboxController(
                runtime = ManagedSandboxRuntime(TestLauncher(root), FileExecutionLogRepository(File(root, "logs")), sessionId = "session-trace"),
                rootfsDir = root,
                capabilityResolver = CapabilityResolver(mapOf(
                    "sandbox.health" to { _: List<String> ->
                        CapabilityResolver.Resolution.Comando(listOf("sh", "-c", "printf sandbox-health-ok"))
                    }
                ))
            )

            val cycle = controller.healthCheck("trace-run")

            assertTrue("cycle=$cycle", cycle.aprovado)
            // O traceId de cada TraceEvent é o actionId do ActionRequest (o mesmo correlator
            // já usado pelo ActionAuditLog), não o runId do ciclo — por isso a leitura aqui não
            // filtra por "trace-run" e sim confirma os estágios produzidos pelo ActionGateway
            // real para a única action deste ciclo (a capability sandbox.health).
            val traceEvents = controller.executionTrace()
            assertTrue("traceEvents vazio", traceEvents.isNotEmpty())
            val stages = traceEvents.map { it.stage }.toSet()
            assertTrue("stages=$stages", TraceStage.TASK in stages)
            assertTrue("stages=$stages", TraceStage.CAPABILITY in stages)
            assertTrue("stages=$stages", TraceStage.POLICY in stages)
            assertTrue("stages=$stages", TraceStage.EVIDENCE in stages)
            assertTrue("stages=$stages", TraceStage.SANDBOX in stages)
            val distinctTraceIds = traceEvents.map { it.traceId }.toSet()
            assertTrue("traceIds=$distinctTraceIds", distinctTraceIds.size == 1)
            assertTrue("traceIds=$distinctTraceIds", controller.executionTrace(distinctTraceIds.single()).size == traceEvents.size)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `filtrar executionTrace por um traceId especifico isola apenas os eventos daquela action`() {
        val root = Files.createTempDirectory("trace-isolation-").toFile()
        try {
            val controller = BrainSandboxController(
                runtime = ManagedSandboxRuntime(TestLauncher(root), FileExecutionLogRepository(File(root, "logs")), sessionId = "session-trace-2"),
                rootfsDir = root,
                capabilityExecutors = mapOf("chat.respond" to ActionExecutor { _, _, _ -> ActionExecution(true, result = "ok", evidence = listOf("chat:test")) })
            )

            controller.healthCheck("trace-a")
            controller.executeObjective("Oi, tudo bem?", "trace-b", intent = DeterministicSecretary().classify("Oi, tudo bem?"))

            val all = controller.executionTrace()
            assertTrue(all.size > 1)
            val oneTraceId = all.first().traceId
            val filtered = controller.executionTrace(oneTraceId)
            assertTrue(filtered.isNotEmpty())
            assertTrue(filtered.all { it.traceId == oneTraceId })
            assertTrue(filtered.size < all.size)
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
