package com.sandbox.agent

import com.brain.gateway.ActionExecution
import com.brain.gateway.ActionExecutor
import com.brain.secretary.DeterministicSecretary
import com.brain.secretary.Door
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

    /**
     * Regressão do UI E2E `missingRequirementBecomesClarificationQuestion`: o app real autoriza todos os
     * providers do catálogo em `authorizedAccountIds`. A porta CHAT não pode herdar essa lista — senão a
     * Policy nega o passo `chat.respond` e a pergunta de esclarecimento nunca chega ao usuário.
     */
    @Test
    fun `requisito ausente vira pergunta ao usuario mesmo com contas de API autorizadas no app`() {
        val root = Files.createTempDirectory("chat-clarification-").toFile()
        try {
            val controller = BrainSandboxController(
                runtime = ManagedSandboxRuntime(TestLauncher(root), FileExecutionLogRepository(File(root, "logs")), sessionId = "session-chat-clarification"),
                rootfsDir = root,
                authorizedAccountIds = setOf("android:provider-a", "android:provider-b"),
                capabilityExecutors = mapOf(
                    "chat.respond" to ActionExecutor { request, _, _ ->
                        ActionExecution(
                            true,
                            result = "Preciso de um esclarecimento antes de continuar: ${request.parameters["parameter.0"]}",
                            evidence = listOf("chat:clarification-question")
                        )
                    }
                )
            )
            val objective = "Quero discutir fotografia"
            val intent = DeterministicSecretary().classify(objective)
            assertEquals(Door.CHAT, intent.door)

            val cycle = controller.executeObjective(objective, "chat-clarification", intent = intent)

            assertTrue("cycle=$cycle", cycle.aprovado)
            assertFalse("passo negado pela Policy: $cycle", cycle.passos.any { it.status == StatusPasso.NEGADO_PELA_POLICY })
            val step = cycle.passos.single()
            assertEquals("chat.respond", step.capacidade)
            assertEquals(StatusPasso.APROVADO, step.status)
            assertTrue(cycle.resposta!!.contains("Preciso de um esclarecimento"))
            assertTrue(cycle.resposta!!.contains("sujeito principal"))
            assertTrue(cycle.resposta!!.contains("Qual é a sua preferência?"))
            assertFalse(cycle.passos.any { it.capacidade.orEmpty().startsWith("workspace.") || it.capacidade.orEmpty().startsWith("sandbox.") })
            assertTrue(controller.localEvents("chat-clarification").any { it.type == "ClarificationRequested" && it.payload["missing"] == "sujeito principal" })
            assertTrue(step.capacidade == "chat.respond")
            assertTrue(cycle.posExecucao?.selfE2E?.all { it.passed } == true)
            assertEquals(com.brain.validation.ValidationStatus.NEEDS_INPUT, cycle.posExecucao?.doorE2E?.status)
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
