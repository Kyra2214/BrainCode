package com.sandbox.app

import com.brain.prompt.PromptLibrary
import com.brain.prompt.PromptTemplate
import com.brain.research.ResearchResult
import com.brain.research.WebResearchProvider
import com.sandbox.agent.BrainSandboxController
import com.sandbox.runtime.FileExecutionLogRepository
import com.sandbox.runtime.ManagedSandboxRuntime
import com.sandbox.runtime.SandboxProcessLauncher
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Diferente de com.sandbox.agent.WebResearchIntegrationTest (que usa executores fake
 * só para provar o despacho), este teste usa o WebResearchExecutor e o
 * PromptGenerationExecutor REAIS de produção — prova que o texto pesquisado realmente
 * chega ao Prompt Creator e aparece na resposta final entregue ao usuário.
 */
class PromptCreatorWebResearchIntegrationTest {

    private class FakePromptLibrary : PromptLibrary {
        val templates = ConcurrentHashMap<String, PromptTemplate>()
        override suspend fun buscarPorContexto(contextoDeUso: String): List<PromptTemplate> = templates.values.toList()
        override suspend fun salvarNovaVersao(template: PromptTemplate): String { templates[template.id] = template; return template.id }
        override suspend fun registrarResultado(templateId: String, sucesso: Boolean, custo: Double, tempoMs: Long) = Unit
        override suspend fun aposentar(templateId: String): Boolean {
            val atual = templates[templateId] ?: return false
            templates[templateId] = atual.copy(aposentado = true)
            return true
        }
    }

    private val iaIndisponivel = PromptImprover { _, _ -> error("nenhuma API configurada neste teste") }

    private class TestLauncher(private val rootDir: File) : SandboxProcessLauncher {
        override fun launch(command: List<String>, workingDir: String): Process {
            val hostDir = File(rootDir, workingDir.removePrefix("/")).apply { mkdirs() }
            return ProcessBuilder(command).directory(hostDir).start()
        }
    }

    private fun controller(root: File, library: PromptLibrary): BrainSandboxController {
        val logDir = File(root, "logs")
        val runtime = ManagedSandboxRuntime(TestLauncher(root), FileExecutionLogRepository(logDir), sessionId = "session-1")
        val providerComResultado = WebResearchProvider { query, _ ->
            Result.success(listOf(
                ResearchResult(
                    query = query,
                    source = "fotografia-tecnica.exemplo",
                    title = "Guia de iluminação de três pontos para still fotorrealista",
                    url = "https://fotografia-tecnica.exemplo/tres-pontos",
                    relevantContent = "use iluminação de três pontos com luz de preenchimento suave para reduzir sombras duras em still fotorrealista",
                    retrievedAt = Instant.now()
                )
            ))
        }
        return BrainSandboxController(
            runtime = runtime,
            rootfsDir = root,
            promptLibrary = library,
            capabilityExecutors = mapOf(
                "sandbox.info" to WebResearchExecutor(providerComResultado),
                "prompt.library.generate" to PromptGenerationExecutor(promptLibrary = library, improver = iaIndisponivel)
            )
        )
    }

    @Test fun `contexto pesquisado chega de verdade ao texto final do prompt entregue`() {
        val root = Files.createTempDirectory("prompt-web-research-").toFile()
        try {
            val library = FakePromptLibrary()
            val cycle = controller(root, library).executeObjective(
                "Pesquise as técnicas mais atuais para escrever prompts fotográficos fotorrealistas e depois " +
                    "crie um prompt de um foguete espacial decolando.",
                "run-prompt-web-research"
            )

            assertTrue("ciclo deveria aprovar pesquisar + produzir", cycle.aprovado)
            val resposta = cycle.resposta.orEmpty()
            assertTrue(
                "a resposta final deveria conter o conteúdo trazido pela pesquisa, não só o pedido original",
                resposta.contains("iluminação de três pontos") || resposta.contains("fotografia-tecnica.exemplo")
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun `prompt visual concreto inclui o contexto da pesquisa na resposta`() {
        val root = Files.createTempDirectory("prompt-web-research-").toFile()
        try {
            val library = FakePromptLibrary()
            val cycle = controller(root, library).executeObjective(
                "Crie um prompt para um foguete espacial decolando.",
                "run-prompt-sem-pesquisa"
            )

            assertTrue(cycle.aprovado)
            assertTrue("prompt visual concreto deveria incluir a pesquisa", cycle.resposta.orEmpty().contains("fotografia-tecnica.exemplo"))
        } finally {
            root.deleteRecursively()
        }
    }
}
