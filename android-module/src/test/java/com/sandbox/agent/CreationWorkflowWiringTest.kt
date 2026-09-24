package com.sandbox.agent

import com.brain.gateway.ActionExecution
import com.brain.gateway.ActionExecutor
import com.brain.planner.PassoPlano
import com.brain.planner.PlanoExecucao
import com.brain.secretary.DeterministicSecretary
import com.brain.secretary.SecretaryState
import com.sandbox.runtime.FileExecutionLogRepository
import com.sandbox.runtime.ManagedSandboxRuntime
import com.sandbox.runtime.SandboxProcessLauncher
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Item 8: CreationWorkflowExecutor plugado no fluxo real da Porta 3 (só depois de APPROVED). */
class CreationWorkflowWiringTest {
    private fun controller(root: File) = BrainSandboxController(
        runtime = ManagedSandboxRuntime(TestLauncher(root), FileExecutionLogRepository(File(root, "logs")), sessionId = "s"),
        rootfsDir = root,
        capabilityExecutors = mapOf("workspace.generate" to ActionExecutor { _, _, _ -> ActionExecution(true, result = "criado", evidence = listOf("workspace:test")) })
    )

    private fun BrainSandboxController.promptEvents(runId: String) =
        localEvents(runId).count { it.type == "workflow.tarefa.prompt_generated" }

    @Test
    fun `intent ja aprovada gera prompts do roadmap antes de executar`() {
        val root = Files.createTempDirectory("cw-approved-").toFile()
        try {
            val controller = controller(root)
            val initial = DeterministicSecretary().classify("criar um aplicativo de notas")
            val approved = SecretaryState().designate(initial).approve().currentIntent!!

            controller.executeObjective(initial.originalPrompt, "cw-approved", intent = approved)

            assertEquals(6, controller.promptEvents("cw-approved"))
            assertEquals(6, controller.creationExecutions("cw-approved").size)
            val types = controller.localEvents("cw-approved").map { it.type }
            assertTrue(types.indexOf("RoadmapCreated") < types.indexOf("workflow.execution.started"))
            assertTrue(types.contains("CreationPromptsGenerated"))
            assertTrue(controller.localEventsHealthy())
        } finally { root.deleteRecursively() }
    }

    @Test
    fun `criacao pendente so gera prompts depois da aprovacao e do resume`() {
        val root = Files.createTempDirectory("cw-pending-").toFile()
        try {
            val controller = controller(root)
            val intent = DeterministicSecretary().classify("criar um aplicativo de notas")
            val pending = controller.executeObjective(intent.originalPrompt, "cw-pending", intent = intent)
            val approvalId = requireNotNull(pending.passos.single().approvalId)

            assertEquals(0, controller.promptEvents("cw-pending"))
            assertTrue(controller.creationExecutions("cw-pending").isEmpty())

            assertTrue(controller.approve(approvalId))
            controller.resumePlan(PlanoExecucao(intent.originalPrompt, listOf(PassoPlano("produzir", "workspace.write", "artefato criado"))), "cw-pending", approvalId)

            assertEquals(6, controller.promptEvents("cw-pending"))
        } finally { root.deleteRecursively() }
    }

    @Test
    fun `resume com aprovacao invalida nao gera prompts`() {
        val root = Files.createTempDirectory("cw-rejected-").toFile()
        try {
            val controller = controller(root)
            val intent = DeterministicSecretary().classify("criar um aplicativo de notas")
            controller.executeObjective(intent.originalPrompt, "cw-rejected", intent = intent)

            controller.resumePlan(PlanoExecucao(intent.originalPrompt, listOf(PassoPlano("produzir", "workspace.write", "artefato criado"))), "cw-rejected", "aprovacao-inexistente")

            assertEquals(0, controller.promptEvents("cw-rejected"))
        } finally { root.deleteRecursively() }
    }

    private class TestLauncher(private val rootDir: File) : SandboxProcessLauncher {
        override fun launch(command: List<String>, workingDir: String): Process =
            ProcessBuilder(command).directory(File(rootDir, workingDir.removePrefix("/")).apply { mkdirs() }).start()
    }
}
