package com.sandbox.agent

import com.brain.gateway.ActionExecution
import com.brain.gateway.ActionExecutor
import com.brain.planner.PassoPlano
import com.brain.planner.PlanoExecucao
import com.sandbox.runtime.FileExecutionLogRepository
import com.sandbox.runtime.ManagedSandboxRuntime
import com.sandbox.runtime.SandboxProcessLauncher
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RevisionRetryIntegrationTest {
    @Test
    fun `falha critica revisa corrige gera nova evidencia e passa na segunda tentativa`() {
        val root = Files.createTempDirectory("revision-retry-").toFile()
        try {
            val executions = mutableListOf<List<String>>()
            val executor = ActionExecutor { request, _, _ ->
                executions += request.parameters.values.toList()
                val corrected = request.parameters.values.any { it.contains("brain-revision-attempt:2") }
                if (corrected) {
                    ActionExecution(
                        success = true,
                        result = "expected token",
                        evidence = listOf("sandbox-test:attempt-2")
                    )
                } else {
                    ActionExecution(success = false, result = "first failure", error = "deliberate failure")
                }
            }
            val controller = controller(root, mapOf("sandbox.test" to executor))
            val plan = PlanoExecucao(
                objetivo = "corrigir falha determinística",
                passos = listOf(PassoPlano("retry", "sandbox.test", "expected token"))
            )

            val result = controller.executePlan(plan, runId = "run-revision")

            assertTrue("segunda tentativa deveria ser entregue", result.aprovado)
            assertEquals(2, executions.size)
            assertEquals("run-revision", result.runId)
            assertEquals(1, result.posExecucao?.revisionAttempts?.size)
            assertEquals("run-revision:attempt-1", result.posExecucao?.revisionAttempts?.single()?.runId)
            assertEquals("REVISE", result.posExecucao?.revisionAttempts?.single()?.outcome)
            val events = controller.localEvents("run-revision")
            assertTrue(events.any { it.type == "AttemptStarted" && it.payload["attemptRunId"] == "run-revision:attempt-1" })
            assertTrue(events.any { it.type == "FixVerifyLearnCompleted" })
            assertTrue(events.any { it.type == "Delivered" })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `retry falha no maximo em tres tentativas e registra abort`() {
        val root = Files.createTempDirectory("revision-limit-").toFile()
        try {
            var executions = 0
            val executor = ActionExecutor { _, _, _ ->
                executions++
                ActionExecution(success = false, result = "first failure", error = "always fails")
            }
            val controller = controller(root, mapOf("sandbox.test" to executor))
            val plan = PlanoExecucao(
                objetivo = "não entrar em loop",
                passos = listOf(PassoPlano("retry", "sandbox.test", "expected token"))
            )

            val result = controller.executePlan(plan, runId = "run-limit")

            assertTrue(!result.aprovado)
            assertEquals(3, executions)
            assertTrue(result.posExecucao?.issues?.contains("revision.max-attempts-exceeded") == true)
            assertTrue(controller.localEvents("run-limit").any { it.type == "RevisionAborted" })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `excecao tecnica repete a mesma tentativa antes de revisar`() {
        val root = Files.createTempDirectory("technical-retry-").toFile()
        try {
            var calls = 0
            val executor = ActionExecutor { _, _, _ ->
                calls++
                if (calls == 1) error("timeout transitório")
                ActionExecution(success = true, result = "resultado estável", evidence = listOf("technical-retry-ok"))
            }
            val controller = controller(root, mapOf("sandbox.test" to executor))
            val plan = PlanoExecucao("retry técnico", listOf(PassoPlano("step", "sandbox.test", "resultado estável")))

            val result = controller.executePlan(plan, runId = "run-technical")

            assertTrue(result.aprovado)
            assertEquals(2, calls)
            assertTrue(controller.localEvents("run-technical").any { it.type == "TechnicalRetry" })
            assertTrue(result.posExecucao?.revisionAttempts.orEmpty().isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    private fun controller(root: File, executors: Map<String, ActionExecutor>): BrainSandboxController {
        val runtime = ManagedSandboxRuntime(
            TestLauncher(root),
            FileExecutionLogRepository(File(root, "logs")),
            sessionId = "session-revision"
        )
        return BrainSandboxController(runtime, root, capabilityExecutors = executors)
    }

    private class TestLauncher(private val rootDir: File) : SandboxProcessLauncher {
        override fun launch(command: List<String>, workingDir: String): Process {
            val hostDir = File(rootDir, workingDir.removePrefix("/")).apply { mkdirs() }
            return ProcessBuilder(command).directory(hostDir).start()
        }
    }
}
