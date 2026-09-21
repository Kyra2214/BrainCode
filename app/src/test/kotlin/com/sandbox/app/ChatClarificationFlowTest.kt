package com.sandbox.app

import com.brain.secretary.DeterministicSecretary
import com.brain.secretary.Door
import com.sandbox.agent.BrainSandboxController
import com.sandbox.agent.StatusPasso
import com.sandbox.runtime.FileExecutionLogRepository
import com.sandbox.runtime.ManagedSandboxRuntime
import com.sandbox.runtime.SandboxProcessLauncher
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cadeia real da Porta 1 até o texto que a UI mostra: Secretário → RequirementGate → chat.respond
 * (ChatResponseExecutor de verdade) → mapeamento do ViewModel. Usa `authorizedAccountIds` não vazio
 * porque é assim que o app monta o controller; sem isso o bug do E2E não aparece em teste unitário.
 */
class ChatClarificationFlowTest {
    @Test
    fun `pedido sem sujeito vira pergunta de esclarecimento visivel ao usuario`() {
        val root = Files.createTempDirectory("chat-clarification-flow-").toFile()
        try {
            val controller = BrainSandboxController(
                runtime = ManagedSandboxRuntime(TestLauncher(root), FileExecutionLogRepository(File(root, "logs")), sessionId = "session-chat-flow"),
                rootfsDir = root,
                authorizedAccountIds = setOf("android:provider-a", "android:provider-b"),
                capabilityExecutors = mapOf("chat.respond" to ChatResponseExecutor())
            )
            val objective = "Faça isso."
            val intent = DeterministicSecretary().classify(objective)
            assertEquals(Door.CHAT, intent.door)

            val cycle = controller.executeObjective(objective, "chat-flow", intent = intent)

            // Mesmo mapeamento do SandboxViewModel.sendChatMessage.
            val content = cycle.resposta ?: "Plano concluído: ${cycle.aprovado}"
            val type = detectGeneratedContentType(
                content,
                cycle.passos.lastOrNull { it.resultado != null }?.capacidade,
                cycle.passos.flatMap { it.executionEvidence }
            )

            assertTrue("cycle=$cycle", cycle.aprovado)
            assertFalse(cycle.passos.any { it.status == StatusPasso.NEGADO_PELA_POLICY })
            assertTrue(content, content.startsWith("Preciso de um esclarecimento"))
            assertTrue(content, content.contains("Qual ação ou objeto"))
            assertEquals(GeneratedContentType.CLARIFICATION, type)
            assertFalse(cycle.passos.any { it.capacidade.orEmpty().startsWith("workspace.") || it.capacidade.orEmpty().startsWith("sandbox.") })
            assertTrue(controller.localEvents("chat-flow").any { it.type == "IntentEnvelopeCreated" && it.payload["route"] == "CLARIFY" })
            assertFalse(controller.localEvents("chat-flow").any { it.type == "PlanningArtifactCreated" })
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
