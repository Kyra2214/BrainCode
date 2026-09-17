package com.brain.reasoning

import org.junit.Assert.assertTrue
import org.junit.Test

class RevisionEngineTest {
    @Test
    fun `revision engine nunca ultrapassa tres revisoes`() {
        val state = ReasoningEngine().analyze("crie uma fotografia de um foguete com céu estrelado ao fundo")
        val result = RevisionEngine(maxRevisions = 2).revise(state, "Fotografia de um foguete, ambientado em fundo genérico. Composição: equilibrada.")

        assertTrue(result.revisions <= 2)
    }
}
