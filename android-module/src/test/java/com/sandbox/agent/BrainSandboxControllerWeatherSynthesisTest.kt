package com.sandbox.agent

import com.brain.gateway.ActionExecution
import com.brain.gateway.ActionExecutor
import com.brain.secretary.UserResponse
import com.sandbox.runtime.FileExecutionLogRepository
import com.sandbox.runtime.ManagedSandboxRuntime
import com.sandbox.runtime.SandboxProcessLauncher
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regressão: uma pergunta classificada como WEATHER (ou RESEARCH) resolve
 * envelope.route=CAPABILITY com targetCapability="network.research" (ver
 * BrainInputInterpreter). O WebResearchExecutor real nunca preenche
 * `userResponse` (de propósito — só chat.respond/ChatResponseExecutor pode
 * liberar texto ao usuário). Antes da correção, fastPathPlan gerava um plano
 * de UM passo só (o "network.research"), então cycle.resposta ficava null
 * mesmo com a pesquisa concluída com sucesso, e a UI caía no fallback
 * "Plano concluído: true" — ver relato do usuário sobre "tempo em Macaé RJ".
 */
class BrainSandboxControllerWeatherSynthesisTest {
    @Test
    fun `pergunta de clima encadeia pesquisa e resposta, e cycle resposta nao fica nula`() {
        val root = Files.createTempDirectory("weather-synthesis-").toFile()
        try {
            var chatRecebeuContextoDaPesquisa = false
            val fakeResearch = ActionExecutor { _, _, _ ->
                ActionExecution(
                    success = true,
                    internalPayload = "Macaé, RJ: 28°C, poucas nuvens, sem previsão de chuva.",
                    evidence = listOf("web-research:source=climatempo.com.br")
                )
            }
            val fakeChat = ActionExecutor { request, _, _ ->
                if (request.parameters.values.any { it.contains("28°C") }) chatRecebeuContextoDaPesquisa = true
                ActionExecution(
                    success = true,
                    userResponse = UserResponse(
                        text = "Em Macaé está fazendo 28°C, com poucas nuvens.",
                        evidence = listOf("chat:conversation:synthesis"),
                        requestId = request.actionId
                    )
                )
            }

            val runtime = ManagedSandboxRuntime(TestLauncher(root), FileExecutionLogRepository(File(root, "logs")), sessionId = "session-weather")
            val controller = BrainSandboxController(
                runtime = runtime,
                rootfsDir = root,
                capabilityExecutors = mapOf(
                    "sandbox.info" to fakeResearch,
                    "network.research" to fakeResearch,
                    "chat.respond" to fakeChat
                )
            )

            val cycle = controller.executeObjective("Qual o tempo em Macaé RJ", "run-weather")

            assertTrue("ciclo deveria aprovar pesquisar + responder", cycle.aprovado)
            assertEquals(listOf("pesquisar", "responder"), cycle.passos.map { it.passoId })
            assertNotNull("cycle.resposta não pode ficar null com a pesquisa concluída", cycle.resposta)
            assertEquals("Em Macaé está fazendo 28°C, com poucas nuvens.", cycle.resposta)
            assertTrue("o passo 'responder' deveria receber o texto da pesquisa como contexto", chatRecebeuContextoDaPesquisa)
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
