package com.brain.prompt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class InMemoryPromptLibraryTest {
    @Test
    fun `biblioteca persiste template e aprendizado entre instancias`() {
        val storage = File.createTempFile("brain-prompt-library", ".db").apply { delete() }
        val template = PromptTemplate("persistencia", 1, "gerar prompt", "imagem foguete realista", "prompt-generation", null, "Crie {OBJETIVO}", 0.6, 0.0, 0)
        val first = InMemoryPromptLibrary(listOf(template), storage)
        runBlockingCompat { first.registrarResultado(template.id, sucesso = true, custo = 0.02, tempoMs = 1200) }
        val reloaded = InMemoryPromptLibrary(emptyList(), storage)
        val atual = reloaded.snapshotTemplates().first { it.id == template.id }
        assertEquals(1, atual.amostrasObservadas)
        assertEquals(1.0, atual.taxaSucesso, 0.0001)
        assertEquals(0.02, atual.custoMedio, 0.0001)
        assertEquals(1200L, atual.tempoMedioMs)
        storage.delete()
    }

    @Test
    fun `seed sem observacoes usa prior neutro em vez de afirmar sucesso real`() {
        val storage = File.createTempFile("brain-prompt-prior", ".db").apply { delete() }
        val template = PromptTemplate("seed", 1, "imagem realista", "fotografia imagem", null, null, "prompt", 0.6, 0.0, 0)
        val library = InMemoryPromptLibrary(listOf(template), storage)
        assertEquals(0, library.snapshotTemplates().first().amostrasObservadas)
        assertTrue(library.snapshotTemplates().first().taxaSucesso >= 0.0)
        storage.delete()
    }

    @Test
    fun `templates quase iguais sao consolidados em nova versao`() {
        val storage = File.createTempFile("brain-prompt-dedup", ".db").apply { delete() }
        val library = InMemoryPromptLibrary(listOf(
            PromptTemplate("base", 1, "gerar imagem realista", "imagem fotografia", null, null, "Crie uma fotografia realista de um foguete", 0.8, 0.0, 0)
        ), storage)
        runBlockingCompat {
            library.salvarNovaVersao(PromptTemplate("novo", 1, "gerar imagem realista", "imagem fotografia", null, null, "Crie uma fotografia realista de um foguete", 0.5, 0.0, 0))
        }
        val matches = library.snapshotTemplates().filter { it.id == "base" }
        assertEquals(1, matches.size)
        assertEquals(2, matches.first().versao)
        storage.delete()
    }

    private fun runBlockingCompat(block: suspend () -> Unit) = kotlinx.coroutines.runBlocking { block() }
}
