package com.sandbox.agent

import com.brain.gateway.ActionExecution
import com.brain.gateway.ActionExecutor
import com.brain.secretary.DeterministicSecretary
import com.sandbox.runtime.FileExecutionLogRepository
import com.sandbox.runtime.ManagedSandboxRuntime
import com.sandbox.runtime.SandboxProcessLauncher
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrainSandboxControllerChatTest {
    @Test
    fun `porta chat responde sem iniciar producao ou execucao`() {
        val root = Files.createTempDirectory("chat-port-").toFile()
        try {
            val controller = BrainSandboxController(
                runtime = ManagedSandboxRuntime(TestLauncher(root), FileExecutionLogRepository(File(root, "logs")), sessionId = "session-chat"),
                rootfsDir = root,
                capabilityExecutors = mapOf(
                    "chat.respond" to ActionExecutor { _, _, _ ->
                        ActionExecution(true, result = "Resposta conversacional local sobre interface/aplicativo", evidence = listOf("chat:test"))
                    }
                )
            )
            val objective = "Estou pensando em criar um aplicativo, quero organizar a ideia"
            val intent = DeterministicSecretary().classify(objective)

            val cycle = controller.executeObjective(objective, "chat-port", intent = intent)

            assertTrue("cycle=$cycle", cycle.aprovado)
            assertEquals(listOf("chat.respond"), cycle.passos.map { it.capacidade })
            assertTrue(cycle.passos.single().resultado!!.contains("Resposta conversacional"))
            assertFalse(cycle.passos.any { it.capacidade.orEmpty().startsWith("workspace.") || it.capacidade.orEmpty().startsWith("sandbox.") })
            assertTrue(controller.localEvents("chat-port").any { it.type == "DoorDesignated" && it.payload["door"] == "CHAT" })
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
