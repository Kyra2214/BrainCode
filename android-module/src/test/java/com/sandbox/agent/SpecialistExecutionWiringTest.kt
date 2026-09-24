package com.sandbox.agent

import com.brain.core.Roadmap
import com.brain.core.TarefaStatus
import com.brain.gateway.ActionExecution
import com.brain.gateway.ActionExecutor
import com.brain.prompt.DefaultPromptGenerator
import com.brain.prompt.PromptGenerator
import com.brain.prompt.PromptLibrary
import com.brain.prompt.PromptGerado
import com.brain.secretary.DeterministicSecretary
import com.brain.secretary.SecretaryState
import com.sandbox.runtime.FileExecutionLogRepository
import com.sandbox.runtime.ManagedSandboxRuntime
import com.sandbox.runtime.SandboxProcessLauncher
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Item 8 (limitações): execução dos especialistas pelo ActionGateway e geração de prompts que realmente suspende. */
class SpecialistExecutionWiringTest {
    private val workspace = ActionExecutor { _, _, _ -> ActionExecution(true, result = "interface do aplicativo de notas criado", evidence = listOf("workspace:test")) }

    private fun controller(
        root: File,
        executors: Map<String, ActionExecutor>,
        promptGenerator: PromptGenerator = DefaultPromptGenerator()
    ) = BrainSandboxController(
        runtime = ManagedSandboxRuntime(TestLauncher(root), FileExecutionLogRepository(File(root, "logs")), sessionId = "s"),
        rootfsDir = root,
        capabilityExecutors = executors,
        promptGenerator = promptGenerator
    )

    private fun approvedIntent(): com.brain.secretary.OrderIntent {
        val initial = DeterministicSecretary().classify("criar um aplicativo de notas")
        return SecretaryState().designate(initial).approve().currentIntent!!
    }

    @Test
    fun `especialistas rodam pelo gateway depois do build aprovado e agent code reaproveita o ciclo`() {
        val root = Files.createTempDirectory("spec-exec-").toFile()
        try {
            val calls = CopyOnWriteArrayList<String>()
            val specialist = ActionExecutor { request, _, _ ->
                calls += request.parameters["parameter.1"].orEmpty()
                ActionExecution(true, result = "entregável do ${request.parameters["parameter.1"]} com conteúdo suficiente", evidence = listOf("provider:test"))
            }
            val controller = controller(root, mapOf("workspace.generate" to workspace, "specialist.execute" to specialist))
            val intent = approvedIntent()

            val cycle = controller.executeObjective(intent.originalPrompt, "spec-run", intent = intent)

            assertEquals("cycle=$cycle", StatusPasso.APROVADO, cycle.passos.first().status)
            val results = controller.creationTaskResults("spec-run")
            assertEquals(6, results.size)
            assertTrue("results=$results", results.all { it.status == TarefaStatus.APROVADA })
            assertEquals(listOf("agent.requirements", "agent.architecture", "agent.integration", "agent.test", "agent.release"), calls.toList())
            assertFalse(calls.contains("agent.code"))
            val types = controller.localEvents("spec-run").map { it.type }
            assertTrue(types.indexOf("StepCompleted") < types.indexOf("workflow.specialists.started"))
            assertTrue(types.contains("SpecialistTasksCompleted"))
            assertTrue(controller.localEventsHealthy())
        } finally { root.deleteRecursively() }
    }

    @Test
    fun `sem executor de especialista a execucao e pulada e os prompts continuam gerados`() {
        val root = Files.createTempDirectory("spec-skip-").toFile()
        try {
            val controller = controller(root, mapOf("workspace.generate" to workspace))
            val intent = approvedIntent()

            controller.executeObjective(intent.originalPrompt, "spec-skip", intent = intent)

            assertEquals(6, controller.creationExecutions("spec-skip").size)
            assertTrue(controller.creationTaskResults("spec-skip").isEmpty())
            assertTrue(controller.localEvents("spec-skip").any { it.type == "SpecialistExecutionSkipped" && it.payload["reason"] == "executor-not-configured" })
        } finally { root.deleteRecursively() }
    }

    @Test
    fun `falha do provider reprova a tarefa, bloqueia dependentes e nao altera o ciclo do usuario`() {
        val root = Files.createTempDirectory("spec-fail-").toFile()
        try {
            val failing = ActionExecutor { _, _, _ -> ActionExecution(false, error = "provider indisponível") }
            val controller = controller(root, mapOf("workspace.generate" to workspace, "specialist.execute" to failing))
            val intent = approvedIntent()

            val cycle = controller.executeObjective(intent.originalPrompt, "spec-fail", intent = intent)

            assertEquals(StatusPasso.APROVADO, cycle.passos.first().status)
            val results = controller.creationTaskResults("spec-fail")
            assertEquals(TarefaStatus.REPROVADA, results.first().status)
            assertTrue(results.drop(1).all { it.blocked })
        } finally { root.deleteRecursively() }
    }

    @Test
    fun `gerador de prompts que suspende em outra thread nao quebra o controller`() {
        val root = Files.createTempDirectory("spec-suspend-").toFile()
        try {
            val delegate = DefaultPromptGenerator()
            val suspending = object : PromptGenerator {
                override suspend fun gerarPromptsPorRoadmap(roadmap: Roadmap, library: PromptLibrary): List<PromptGerado> {
                    suspendCoroutine<Unit> { cont -> thread { Thread.sleep(50); cont.resume(Unit) } }
                    return delegate.gerarPromptsPorRoadmap(roadmap, library)
                }
                override suspend fun gerarPromptDeCorrecao(tarefa: com.brain.core.Tarefa, motivoReprovacao: String, library: PromptLibrary) =
                    delegate.gerarPromptDeCorrecao(tarefa, motivoReprovacao, library)
            }
            val controller = controller(root, mapOf("workspace.generate" to workspace), suspending)
            val intent = approvedIntent()

            controller.executeObjective(intent.originalPrompt, "spec-suspend", intent = intent)

            assertEquals(6, controller.creationExecutions("spec-suspend").size)
            assertFalse(controller.localEvents("spec-suspend").any { it.type == "CreationPromptsFailed" })
        } finally { root.deleteRecursively() }
    }

    private class TestLauncher(private val rootDir: File) : SandboxProcessLauncher {
        override fun launch(command: List<String>, workingDir: String): Process =
            ProcessBuilder(command).directory(File(rootDir, workingDir.removePrefix("/")).apply { mkdirs() }).start()
    }
}
