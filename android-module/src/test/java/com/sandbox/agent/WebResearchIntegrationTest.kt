package com.sandbox.agent

import com.brain.gateway.ActionExecution
import com.brain.gateway.ActionExecutor
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
 * Prova, com evidência de execução real (não só leitura de código), que:
 * 1) "pesquisar" é descoberto e despachado pelo mesmo mecanismo de capabilities/Dispatcher;
 * 2) o resultado da pesquisa chega como parâmetro extra ao passo dependente ("produzir");
 * 3) um pedido sem gatilho de pesquisa NÃO aciona a capability de pesquisa.
 */
class WebResearchIntegrationTest {

    private fun controller(root: File, executors: Map<String, ActionExecutor>): BrainSandboxController {
        val logDir = File(root, "logs")
        val runtime = ManagedSandboxRuntime(TestLauncher(root), FileExecutionLogRepository(logDir), sessionId = "session-1")
        return BrainSandboxController(
            runtime = runtime,
            rootfsDir = root,
            capabilityExecutors = executors
        )
    }

    @Test
    fun `pedido com pesquisa desperta network research e propaga contexto ao passo produzir`() {
        val root = Files.createTempDirectory("web-research-").toFile()
        try {
            val requestsRecebidas = mutableListOf<Map<String, String>>()
            val fakeResearch = ActionExecutor { request, capability, _ ->
                ActionExecution(success = true, result = "Contexto de pesquisa web (1 fonte(s)):\n- Guia de fotografia (exemplo.com): use iluminação de três pontos [https://exemplo.com]", evidence = listOf("web-research:source=exemplo.com"))
            }
            val fakeProduzir = ActionExecutor { request, capability, _ ->
                requestsRecebidas += request.parameters
                ActionExecution(success = true, result = "prompt fake gerado")
            }

            val ctl = controller(root, mapOf("sandbox.info" to fakeResearch, "prompt.library.generate" to fakeProduzir))
            val cycle = ctl.executeObjective(
                "Pesquise as técnicas mais atuais para escrever prompts fotográficos fotorrealistas e depois crie um prompt de um foguete espacial decolando.",
                "run-web-research"
            )

            assertTrue("ciclo deveria aprovar todos os passos (pesquisar + produzir)", cycle.aprovado)
            assertEquals(1, requestsRecebidas.size)
            val parametrosDoProduzir = requestsRecebidas.single()
            assertTrue(
                "produzir deveria receber o resultado da pesquisa como parâmetro adicional",
                parametrosDoProduzir.values.any { it.contains("iluminação de três pontos") }
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `pedido de prompt sem gatilho de pesquisa nao aciona network research`() {
        val root = Files.createTempDirectory("web-research-").toFile()
        try {
            var researchChamada = false
            val fakeResearch = ActionExecutor { _, _, _ -> researchChamada = true; ActionExecution(success = true, result = "não deveria ter sido chamado") }
            val fakeProduzir = ActionExecutor { _, _, _ -> ActionExecution(success = true, result = "prompt fake gerado") }

            val ctl = controller(root, mapOf("sandbox.info" to fakeResearch, "prompt.library.generate" to fakeProduzir))
            val cycle = ctl.executeObjective("Crie um prompt para um foguete espacial decolando.", "run-sem-pesquisa")

            assertTrue(cycle.aprovado)
            assertFalse("WebResearch não deveria ser acionado sem gatilho de pesquisa no pedido", researchChamada)
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
