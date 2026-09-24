package com.brain.retrieval

import com.brain.planner.KeywordFunctionSplitter
import com.brain.prompt.InMemoryPromptLibrary
import com.brain.prompt.PromptTemplate
import com.brain.router.PapelPipeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptLibraryRetrievalSourceTest {
    @Test
    fun `prompt library real vira fonte de retrieval antes de APIs`() {
        val library = InMemoryPromptLibrary(listOf(
            PromptTemplate("room", 1, "gerar model Room", "model Room a partir de entidade", null, null, "Use Room", .9, 0.0, 1)
        ))
        val result = Retrieval(listOf(
            PromptLibraryRetrievalSource.from(library),
            RetrievalSource("apis", RetrievalLayer.APIS, RetrievalLookup { error("API não deveria ser chamada") })
        )).retrieve(RetrievalQuery("gerar model Room"))
        assertEquals(RetrievalStatus.FOUND, result.status)
        assertEquals("room", result.hit?.id)
        assertEquals(listOf(RetrievalLayer.PROMPT_LIBRARY), result.attemptedLayers)
    }

    @Test
    fun `documento nao recebe papel de escrita de prompt`() {
        val splitter = KeywordFunctionSplitter()
        val document = splitter.split("criar documento de arquitetura").single()
        val prompt = splitter.split("criar prompt de arquitetura").single()
        assertEquals(PapelPipeline.PRODUCAO_DE_ARTEFATO, document.papel)
        assertEquals(PapelPipeline.ESCRITA_DE_PROMPT, prompt.papel)
        assertTrue(document.capacidade != prompt.capacidade)
    }
}
