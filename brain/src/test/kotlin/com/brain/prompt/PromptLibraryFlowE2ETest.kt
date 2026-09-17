package com.brain.prompt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** E2E do ciclo operacional da biblioteca: miss -> gravação -> reuse -> feedback -> restart. */
class PromptLibraryFlowE2ETest {
    @Test
    fun `fluxo completo preserva prompt gerado aprendizado e reuso apos restart`() {
        val storage = File.createTempFile("brain-prompt-e2e", ".db").apply { delete() }
        val seed = PromptTemplate(
            id = "seed-image",
            versao = 1,
            finalidade = "imagem fotografia",
            contextoDeUso = "fotografia de produto",
            skillRelacionada = "prompt-generation",
            agenteRelacionado = "prompt-specialist",
            textoTemplate = "Crie uma fotografia de produto com iluminação profissional",
            taxaSucesso = 0.6,
            custoMedio = 0.0,
            tempoMedioMs = 0L,
            amostrasObservadas = 0
        )

        // 1) Pedido sem compatibilidade suficiente: biblioteca não inventa uma resposta.
        val library = InMemoryPromptLibrary(listOf(seed), storage)
        assertTrue(library.buscarPorContextoSnapshot("aplicativo de controle financeiro").isEmpty())

        // 2) Especialista gera prompt; o executor salvaria antes de devolver ao usuário.
        val generated = PromptTemplate(
            id = "generated-finance",
            versao = 1,
            finalidade = "arquitetura aplicativo financeiro",
            contextoDeUso = "app controle financeiro sqlite android",
            skillRelacionada = "prompt-generation",
            agenteRelacionado = "prompt-specialist",
            textoTemplate = "Projete um app de controle financeiro Android com SQLite e testes",
            taxaSucesso = 0.5,
            custoMedio = 0.0,
            tempoMedioMs = 0L,
            amostrasObservadas = 0
        )
        runBlockingCompat { library.salvarNovaVersao(generated) }

        // 3) Segunda solicitação encontra o conhecimento recém-salvo.
        val reused = library.buscarPorContextoSnapshot("criar app Android de controle financeiro com SQLite")
            .firstOrNull { it.id == "generated-finance" }
        assertTrue("prompt gerado não foi reutilizável", reused != null)

        // 4) Resultado real alimenta o aprendizado.
        repeat(4) { runBlockingCompat { library.registrarResultado("generated-finance", true, 0.01, 1000L) } }
        runBlockingCompat { library.registrarResultado("generated-finance", false, 0.01, 2000L) }

        val learned = library.snapshotTemplates().first { it.id == "generated-finance" }
        assertEquals(5, learned.amostrasObservadas)
        assertEquals(0.8, learned.taxaSucesso, 0.0001)
        assertEquals(0.01, learned.custoMedio, 0.0001)
        assertEquals(1200L, learned.tempoMedioMs)

        // 5) Reinício do processo: aprendizado continua disponível.
        val reloaded = InMemoryPromptLibrary(emptyList(), storage)
        val afterRestart = reloaded.snapshotTemplates().first { it.id == "generated-finance" }
        assertEquals(5, afterRestart.amostrasObservadas)
        assertEquals(0.8, afterRestart.taxaSucesso, 0.0001)
        assertEquals(0.01, afterRestart.custoMedio, 0.0001)
        assertEquals(1200L, afterRestart.tempoMedioMs)
        assertTrue(reloaded.buscarPorContextoSnapshot("app controle financeiro SQLite").any { it.id == "generated-finance" })

        storage.delete()
        File(storage.parentFile, "${storage.name}.stats").delete()
    }

    @Test
    fun `historico longo nao altera estatisticas persistidas`() {
        val storage = File.createTempFile("brain-prompt-e2e-long", ".db").apply { delete() }
        val template = PromptTemplate("long", 1, "teste", "execucao prompt", null, null, "Execute a tarefa", 0.5, 0.0, 0L)
        val library = InMemoryPromptLibrary(listOf(template), storage)
        repeat(150) { index ->
            runBlockingCompat {
                library.registrarResultado("long", sucesso = index < 120, custo = 0.02, tempoMs = 1000L)
            }
        }
        val reloaded = InMemoryPromptLibrary(emptyList(), storage)
        val actual = reloaded.snapshotTemplates().first { it.id == "long" }
        assertEquals(150, actual.amostrasObservadas)
        assertEquals(0.8, actual.taxaSucesso, 0.0001)
        assertEquals(0.02, actual.custoMedio, 0.0001)
        assertEquals(1000L, actual.tempoMedioMs)
        storage.delete()
        File(storage.parentFile, "${storage.name}.stats").delete()
    }

    private fun runBlockingCompat(block: suspend () -> Unit) = kotlinx.coroutines.runBlocking { block() }
}
