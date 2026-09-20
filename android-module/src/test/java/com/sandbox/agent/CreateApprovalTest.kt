package com.sandbox.agent

import com.brain.gateway.ActionExecution
import com.brain.gateway.ActionExecutor
import com.brain.planner.PlanoExecucao
import com.brain.secretary.DeterministicSecretary
import com.brain.secretary.SecretaryState
import com.sandbox.runtime.FileExecutionLogRepository
import com.sandbox.runtime.ManagedSandboxRuntime
import com.sandbox.runtime.SandboxProcessLauncher
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CreateApprovalTest {
    @Test
    fun `criação inicial fica pendente e retoma pelo ApprovalStore`() {
        val root = Files.createTempDirectory("create-pending-").toFile()
        try {
            var writes = 0
            val controller = BrainSandboxController(
                runtime = ManagedSandboxRuntime(TestLauncher(root), FileExecutionLogRepository(File(root, "logs")), sessionId = "session-create-pending"),
                rootfsDir = root,
                capabilityExecutors = mapOf("workspace.generate" to ActionExecutor { _, _, _ -> writes++; ActionExecution(true, result = "criado", evidence = listOf("workspace:test")) })
            )
            val intent = DeterministicSecretary().classify("criar um aplicativo de notas")
            val pending = controller.executeObjective(intent.originalPrompt, "create-pending", intent = intent)
            val approvalId = requireNotNull(pending.passos.single().approvalId)

            assertEquals(StatusPasso.AGUARDANDO_APROVACAO, pending.passos.single().status)
            assertEquals(0, writes)
            assertTrue(controller.approve(approvalId))
            val resumed = controller.resumePlan(PlanoExecucao(intent.originalPrompt, listOf(com.brain.planner.PassoPlano("produzir", "workspace.write", "artefato criado"))), "create-pending", approvalId)

            assertTrue("resumed=$resumed", resumed.passos.any { it.status == StatusPasso.APROVADO })
            assertEquals(1, writes)
            assertTrue(controller.localEvents("create-pending").any { it.type == "CreateApprovalRequested" })
            assertTrue(controller.localEvents("create-pending").any { it.type == "RoadmapCreated" })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `workspace só executa depois de approved`() {
        val root = Files.createTempDirectory("create-approval-").toFile()
        try {
            var writes = 0
            val controller = BrainSandboxController(
                runtime = ManagedSandboxRuntime(TestLauncher(root), FileExecutionLogRepository(File(root, "logs")), sessionId = "session-create"),
                rootfsDir = root,
                capabilityExecutors = mapOf(
                    "workspace.generate" to ActionExecutor { _, _, _ ->
                        writes++
                        ActionExecution(true, result = "interface/aplicativo de notas implementado", evidence = listOf("workspace:test"))
                    }
                )
            )
            val secretary = DeterministicSecretary()
            val initial = secretary.classify("criar um aplicativo de notas")
            val approved = SecretaryState().designate(initial).approve().currentIntent!!

            val cycle = controller.executeObjective(initial.originalPrompt, "create-approval", intent = approved)

            assertTrue("cycle=$cycle", cycle.aprovado)
            assertEquals(1, writes)
            assertTrue(controller.localEvents("create-approval").any { it.type == "DoorDesignated" && it.payload["phase"] == "APPROVED" })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `intenção inicial de criação não executa workspace`() {
        val root = Files.createTempDirectory("create-discussion-").toFile()
        try {
            var writes = 0
            val controller = BrainSandboxController(
                runtime = ManagedSandboxRuntime(TestLauncher(root), FileExecutionLogRepository(File(root, "logs")), sessionId = "session-create-discussion"),
                rootfsDir = root,
                capabilityExecutors = mapOf("workspace.generate" to ActionExecutor { _, _, _ -> writes++; ActionExecution(true, result = "não deveria") })
            )
            val intent = DeterministicSecretary().classify("criar um aplicativo de notas")
            controller.executeObjective(intent.originalPrompt, "create-discussion", intent = intent)

            assertEquals(0, writes)
            assertFalse(controller.localEvents("create-discussion").any { it.type == "DoorDesignated" && it.payload["phase"] == "APPROVED" })
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
