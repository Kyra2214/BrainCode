package com.brain.text

import com.brain.planner.KeywordPlanner
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TriggerLexiconBoundaryTest {
    @Test
    fun `tempo hoje em local nao e pergunta de data e dispara pesquisa factual`() = suspendTest {
        assertFalse(TriggerLexicon.matches("tempo hoje em rio das ostras", TriggerLexicon.PERGUNTAS_DATA))
        assertTrue(TriggerLexicon.matches("tempo hoje em rio das ostras", TriggerLexicon.TEMAS_TEMPO_REAL))
        val plano = KeywordPlanner().planejar("tempo hoje em rio das ostras")
        assertTrue(plano.passos.any { it.capacidade == "network.research" })
    }

    @Test
    fun `hoje seguido de verbo nao casa como data`() {
        assertFalse(TriggerLexicon.matches("hoje eu vou trabalhar", TriggerLexicon.PERGUNTAS_DATA))
        assertFalse(TriggerLexicon.matches("hoje espero que chova", TriggerLexicon.PERGUNTAS_DATA))
    }

    @Test
    fun `pergunta de data conhecida continua casando`() {
        assertTrue(TriggerLexicon.matches("que dia é hoje", TriggerLexicon.PERGUNTAS_DATA))
    }

    @Test
    fun `termos curtos exigem fronteira nos dois lados`() {
        assertFalse(TriggerLexicon.matches("aplicativo", listOf("app")))
        assertTrue(TriggerLexicon.matches("quero criar um app", TriggerLexicon.SUBSTANTIVOS_ENTREGAVEL))
        assertFalse(IntentNegation.hasAllowedOccurrence("aplaplicativo", "app"))
    }

    private fun suspendTest(block: suspend () -> Unit) {
        var failure: Throwable? = null
        block.startCoroutine(object : Continuation<Unit> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(result: Result<Unit>) { failure = result.exceptionOrNull() }
        })
        failure?.let { throw it }
    }
}
