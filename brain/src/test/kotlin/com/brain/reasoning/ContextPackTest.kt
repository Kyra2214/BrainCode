package com.brain.reasoning

import com.brain.prompt.PromptDomain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextPackTest {
    @Test fun `reasoning expõe context pack relevante`() {
        val state = ReasoningEngine().analyze("crie um prompt de um foguete no deserto sem texto")
        assertEquals(state.objective, state.contextPack.objective)
        assertTrue(state.contextPack.requirements.isNotEmpty())
        assertTrue(state.contextPack.constraints.any { it.contains("texto") })
    }

    @Test fun `context pack remove duplicatas e limita histórico`() {
        val state = ReasoningState("objetivo", ReasoningIntent.GENERAL_TEXT, PromptDomain.TEXTO, emptyList(), emptyList(), emptyList())
        val pack = ContextPackBuilder.from(state, listOf("a", "a", "b", "c"), listOf("erro", "erro")).compact(2)
        assertEquals(listOf("a", "b"), pack.relevantHistory)
        assertEquals(listOf("erro"), pack.knownErrors)
    }
}
