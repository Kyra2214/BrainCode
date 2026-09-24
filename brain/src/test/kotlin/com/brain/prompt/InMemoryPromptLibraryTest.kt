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
        File("${storage.absolutePath}.stats").delete()
    }

    @Test
    fun `contadores persistem mesmo quando historico operacional e limitado`() {
        val storage = File.createTempFile("brain-prompt-library-long", ".db").apply { delete() }
        val template = PromptTemplate("longo", 1, "gerar prompt", "imagem foguete realista", "prompt-generation", null, "Crie {OBJETIVO}", 0.6, 0.0, 0)
        val first = InMemoryPromptLibrary(listOf(template), storage)
        runBlockingCompat {
            repeat(120) { first.registrarResultado(template.id, sucesso = true, custo = 0.01, tempoMs = 100) }
            repeat(30) { first.registrarResultado(template.id, sucesso = false, custo = 0.02, tempoMs = 200) }
        }
        val reloaded = InMemoryPromptLibrary(emptyList(), storage)
        val atual = reloaded.snapshotTemplates().first { it.id == template.id }
        assertEquals(150, atual.amostrasObservadas)
        assertEquals(120.0 / 150.0, atual.taxaSucesso, 0.0001)
        assertEquals((120 * 0.01 + 30 * 0.02) / 150.0, atual.custoMedio, 0.0001)
        assertEquals((120 * 100L + 30 * 200L) / 150L, atual.tempoMedioMs)
        storage.delete()
        File("${storage.absolutePath}.stats").delete()
    }

    @Test
    fun `seed sem observacoes usa prior neutro em vez de afirmar sucesso real`() {
        val storage = File.createTempFile("brain-prompt-prior", ".db").apply { delete() }
        val template = PromptTemplate("seed", 1, "imagem realista", "fotografia imagem", null, null, "prompt", 0.6, 0.0, 0)
        val library = InMemoryPromptLibrary(listOf(template), storage)
        assertEquals(0, library.snapshotTemplates().first().amostrasObservadas)
        assertTrue(library.snapshotTemplates().first().taxaSucesso >= 0.0)
        storage.delete()
        File("${storage.absolutePath}.stats").delete()
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
        File("${storage.absolutePath}.stats").delete()
    }

    @Test
    fun `palavras-chave muito diferentes nao consolidam mesmo com texto quase igual`() {
        val storage = File.createTempFile("brain-prompt-keywords", ".db").apply { delete() }
        val corpo = (1..100).joinToString(" ") { "palavra$it" }
        val library = InMemoryPromptLibrary(listOf(
            PromptTemplate("a", 1, "gerar imagem realista", "imagem fotografia", null, null, corpo, 0.8, 0.0, 0)
        ), storage)
        runBlockingCompat {
            library.salvarNovaVersao(PromptTemplate("b", 1, "planilha orcamento mensal", "financas planilha", null, null, corpo, 0.5, 0.0, 0))
        }
        assertEquals(setOf("a", "b"), library.snapshotTemplates().map { it.id }.toSet())
        storage.delete()
        File("${storage.absolutePath}.stats").delete()
    }

    @Test
    fun `busca enxerga palavras-chave novas depois de salvar nova versao`() {
        val storage = File.createTempFile("brain-prompt-cache", ".db").apply { delete() }
        val library = InMemoryPromptLibrary(listOf(
            PromptTemplate("x", 1, "gerar imagem", "imagem foguete", null, null, "texto um", 0.6, 0.0, 0)
        ), storage)
        assertTrue(library.buscarPorContextoSnapshot("planilha orcamento").isEmpty())
        runBlockingCompat {
            library.salvarNovaVersao(PromptTemplate("x", 1, "gerar planilha", "planilha orcamento", null, null, "texto dois", 0.6, 0.0, 0))
        }
        assertTrue(library.buscarPorContextoSnapshot("planilha orcamento").any { it.id == "x" })
        storage.delete()
        File("${storage.absolutePath}.stats").delete()
    }

    private fun runBlockingCompat(block: suspend () -> Unit) = kotlinx.coroutines.runBlocking { block() }
}
