package com.brain.research

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentRelevanceScorerTest {

    @Test
    fun `conteudo que so tem prosa de SEO nao bate com pergunta factual`() {
        val snippet = "Guia de leitura: previsão do tempo. A leitura do tempo ganha sentido quando junta números."
        val score = ContentRelevanceScorer.score(snippet, "qual a temperatura em Macaé hoje")
        assertTrue("snippet de SEO não deveria pontuar bem para uma pergunta de temperatura", score < 0.5)
    }

    @Test
    fun `conteudo real da pagina bate com os termos da pergunta`() {
        val paginaReal = "Macaé hoje: máxima de 29°C, mínima de 22°C, parcialmente nublado."
        val score = ContentRelevanceScorer.score(paginaReal, "qual a temperatura em Macaé hoje")
        assertTrue("conteúdo com o termo geográfico da pergunta deveria pontuar bem", score > 0.5)
    }

    @Test
    fun `consulta sem termos significativos pontua zero sem lancar excecao`() {
        assertEquals(0.0, ContentRelevanceScorer.score("qualquer coisa", "e ou de"), 0.0)
    }

    @Test
    fun `conteudo vazio pontua zero`() {
        assertEquals(0.0, ContentRelevanceScorer.score("", "temperatura em Macaé"), 0.0)
    }
}
