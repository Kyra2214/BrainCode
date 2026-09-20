package com.sandbox.agent

import com.brain.gateway.ActionExecution
import com.brain.gateway.ActionExecutor
import com.brain.secretary.DeterministicSecretary
import com.sandbox.runtime.FileExecutionLogRepository
import com.sandbox.runtime.ManagedSandboxRuntime
import com.sandbox.runtime.SandboxProcessLauncher
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrainSandboxControllerDoorTest {
    @Test
    fun `intent chat nunca chega a workspace ou sandbox code e emite DoorDesignated`() {
        val root = Files.createTempDirectory("door-chat-").toFile()
        try {
            val runtime = ManagedSandboxRuntime(TestLauncher(root), FileExecutionLogRepository(File(root, "logs")), sessionId = "session-1")
            val controller = BrainSandboxController(
                runtime = runtime,
                rootfsDir = root,
                capabilityExecutors = mapOf(
                    "sandbox.diagnose" to ActionExecutor { _, _, _ -> ActionExecution(true, result = "resposta local", evidence = listOf("local:chat")) }
                )
            )
            val intent = DeterministicSecretary().classify("Estou pensando em criar um aplicativo")

            val cycle = controller.executeObjective("Estou pensando em criar um aplicativo", "door-chat", intent = intent)

            assertTrue(cycle.passos.isNotEmpty())
            assertFalse(cycle.passos.any { it.capacidade == "workspace.write" || it.capacidade == "sandbox.code" })
            assertTrue(controller.localEvents("door-chat").any { it.type == "DoorDesignated" && it.payload["door"] == "CHAT" })
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
