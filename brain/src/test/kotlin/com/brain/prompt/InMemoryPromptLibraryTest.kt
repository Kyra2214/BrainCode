package com.brain.prompt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class InMemoryPromptLibraryTest {
    private val storage = File(System.getProperty("java.io.tmpdir") ?: ".", "brain-prompt-library.db")

    @Test
    fun `biblioteca persiste template entre instancias`() {
        storage.delete()
        val template = PromptTemplate(
            id = "teste-persistencia",
            versao = 1,
            finalidade = "gerar prompt de imagem realista",
            contextoDeUso = "imagem foguete fotografia realista",
            skillRelacionada = "prompt-generation",
            agenteRelacionado = "prompt-specialist",
            textoTemplate = "Crie uma imagem realista de {OBJETIVO}.",
            taxaSucesso = 0.6,
            custoMedio = 0.0,
            tempoMedioMs = 0L
        )
        runBlockingCompat { InMemoryPromptLibrary(emptyList()).salvarNovaVersao(template) }
        val reloaded = InMemoryPromptLibrary(emptyList())
        assertTrue(reloaded.snapshotTemplates().any { it.id == template.id })
        storage.delete()
    }

    @Test
    fun `resultado real atualiza taxa de sucesso e metadados`() {
        storage.delete()
        val template = PromptTemplate("teste-aprendizado", 1, "imagem realista", "fotografia imagem", null, null, "prompt", 0.5, 0.0, 0)
        val library = InMemoryPromptLibrary(listOf(template))
        runBlockingCompat { library.registrarResultado(template.id, sucesso = true, custo = 0.02, tempoMs = 1200) }
        val atual = library.snapshotTemplates().first { it.id == template.id }
        assertEquals(1.0, atual.taxaSucesso, 0.0001)
        assertEquals(0.02, atual.custoMedio, 0.0001)
        assertEquals(1200L, atual.tempoMedioMs)
        storage.delete()
    }

    @Test
    fun `templates quase iguais sao consolidados em nova versao`() {
        storage.delete()
        val library = InMemoryPromptLibrary(listOf(
            PromptTemplate("base", 1, "gerar imagem realista", "imagem fotografia", null, null, "Crie uma fotografia realista de um foguete", 0.8, 0.0, 0)
        ))
        runBlockingCompat {
            library.salvarNovaVersao(
                PromptTemplate("novo", 1, "gerar imagem realista", "imagem fotografia", null, null, "Crie uma fotografia realista de um foguete", 0.5, 0.0, 0)
            )
        }
        val matches = library.snapshotTemplates().filter { it.id == "base" }
        assertEquals(1, matches.size)
        assertEquals(2, matches.first().versao)
        storage.delete()
    }

    private fun runBlockingCompat(block: suspend () -> Unit) {
        kotlinx.coroutines.runBlocking { block() }
    }
}
