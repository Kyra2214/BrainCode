package com.brain.prompt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** E2E do ciclo operacional da biblioteca: miss -> gravação -> reuse -> feedback real -> restart. */
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

        val library = InMemoryPromptLibrary(listOf(seed), storage)
        assertTrue(library.buscarPorContextoSnapshot("aplicativo de controle financeiro").isEmpty())

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

        val reused = library.buscarPorContextoSnapshot("criar app Android de controle financeiro com SQLite")
            .firstOrNull { it.id == "generated-finance" }
        assertTrue("prompt gerado não foi reutilizável", reused != null)

        repeat(4) { runBlockingCompat { library.registrarResultado("generated-finance", true, 0.01, 1000L) } }
        runBlockingCompat { library.registrarResultado("generated-finance", false, 0.01, 2000L) }

        val learned = library.snapshotTemplates().first { it.id == "generated-finance" }
        assertEquals(5, learned.amostrasObservadas)
        assertEquals(0.8, learned.taxaSucesso, 0.0001)
        assertEquals(0.01, learned.custoMedio, 0.0001)
        assertEquals(1200L, learned.tempoMedioMs)

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
    fun `uso pendente nao conta como sucesso e feedback real persiste`() {
        val storage = File.createTempFile("brain-prompt-feedback", ".db").apply { delete() }
        val template = PromptTemplate("feedback", 1, "gerar prompt", "app financeiro android", "prompt-generation", null, "Crie {OBJETIVO}", 0.5, 0.0, 0L)
        val library = InMemoryPromptLibrary(listOf(template), storage)
        val tracker = PromptOutcomeTracker(library)

        tracker.markUsed("step:step", template.id)
        assertEquals(0, library.snapshotTemplates().first().amostrasObservadas)
        assertTrue(tracker.recordOutcome("step:step", success = false, cost = 0.02, elapsedMs = 900L))

        val afterFailure = library.snapshotTemplates().first()
        assertEquals(1, afterFailure.amostrasObservadas)
        assertEquals(0.0, afterFailure.taxaSucesso, 0.0001)
        assertEquals(0.02, afterFailure.custoMedio, 0.0001)
        assertEquals(900L, afterFailure.tempoMedioMs)

        tracker.markUsed("step2:step2", template.id)
        assertTrue(tracker.recordOutcome("step2:step2", success = true, cost = 0.01, elapsedMs = 700L))
        val afterSuccess = library.snapshotTemplates().first()
        assertEquals(2, afterSuccess.amostrasObservadas)
        assertEquals(0.5, afterSuccess.taxaSucesso, 0.0001)
        assertEquals(0.015, afterSuccess.custoMedio, 0.0001)
        assertEquals(800L, afterSuccess.tempoMedioMs)

        val reloaded = InMemoryPromptLibrary(emptyList(), storage)
        val persisted = reloaded.snapshotTemplates().first { it.id == template.id }
        assertEquals(2, persisted.amostrasObservadas)
        assertEquals(0.5, persisted.taxaSucesso, 0.0001)

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
