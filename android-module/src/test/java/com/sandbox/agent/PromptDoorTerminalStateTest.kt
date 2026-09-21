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

class PromptDoorTerminalStateTest {
    @Test
    fun `prompt entregue termina na biblioteca sem iniciar criacao`() {
        val root = Files.createTempDirectory("prompt-terminal-").toFile()
        try {
            val controller = BrainSandboxController(
                runtime = ManagedSandboxRuntime(TestLauncher(root), FileExecutionLogRepository(File(root, "logs")), sessionId = "session-prompt"),
                rootfsDir = root,
                capabilityExecutors = mapOf(
                    "prompt.library.write" to ActionExecutor { _, _, _ ->
                        ActionExecution(true, result = "Prompt final: resumir uma ideia de produto", evidence = listOf("prompt:test"))
                    },
                    "prompt.library.generate" to ActionExecutor { _, _, _ ->
                        ActionExecution(true, result = "Prompt final: resumir uma ideia de produto", evidence = listOf("prompt:test"))
                    }
                )
            )
            val objective = "Crie um prompt textual para resumir uma ideia de produto"
            val intent = DeterministicSecretary().classify(objective)
            val cycle = controller.executeObjective(objective, "prompt-terminal", intent = intent)

            assertTrue("cycle=$cycle", cycle.aprovado)
            assertTrue(cycle.passos.any { it.capacidade == "prompt.library.write" && it.status == StatusPasso.APROVADO })
            assertFalse(cycle.passos.any { it.capacidade.orEmpty().startsWith("workspace.") || it.capacidade.orEmpty().startsWith("sandbox.") })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `porta prompt nao e negada pela Policy quando o app autoriza contas de API`() {
        val root = Files.createTempDirectory("prompt-accounts-").toFile()
        try {
            val controller = BrainSandboxController(
                runtime = ManagedSandboxRuntime(TestLauncher(root), FileExecutionLogRepository(File(root, "logs")), sessionId = "session-prompt-accounts"),
                rootfsDir = root,
                authorizedAccountIds = setOf("android:provider-a"),
                capabilityExecutors = mapOf(
                    "prompt.library.write" to ActionExecutor { _, _, _ ->
                        ActionExecution(true, result = "Prompt final: resumir uma ideia de produto", evidence = listOf("prompt:test"))
                    },
                    "prompt.library.generate" to ActionExecutor { _, _, _ ->
                        ActionExecution(true, result = "Prompt final: resumir uma ideia de produto", evidence = listOf("prompt:test"))
                    }
                )
            )
            val objective = "Crie um prompt textual para resumir uma ideia de produto"
            val intent = DeterministicSecretary().classify(objective)
            val cycle = controller.executeObjective(objective, "prompt-accounts", intent = intent)

            assertFalse("passo negado pela Policy: $cycle", cycle.passos.any { it.status == StatusPasso.NEGADO_PELA_POLICY })
            assertTrue("cycle=$cycle", cycle.aprovado)
            assertTrue(cycle.passos.any { it.capacidade == "prompt.library.write" && it.status == StatusPasso.APROVADO })
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
