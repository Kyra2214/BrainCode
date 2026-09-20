package com.brain.planner

import com.brain.secretary.DeterministicSecretary
import com.brain.secretary.Door
import com.brain.secretary.Restriction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptDoorCorpusTest {
    private val secretary = DeterministicSecretary()

    @Test
    fun `prompt concluido nunca vaza para workspace ou sandbox code`() {
        val prompts = listOf(
            "Crie um prompt para uma imagem de um foguete decolando",
            "Quero um template de prompt para uma fotografia de arquitetura",
            "Transforme minha imagem em uma cena cinematográfica"
        )

        prompts.forEach { text ->
            val intent = secretary.classify(text)
            val plan = DoorAwareSplitter().split(intent, text)
            assertEquals(text, Door.PROMPT, intent.door)
            assertTrue(text, plan.any { it.capacidade == "prompt.library.write" })
            assertFalse(text, plan.any { it.capacidade.startsWith("workspace.") || it.capacidade == "sandbox.code" })
        }
    }

    @Test
    fun `no web remove pesquisa visual automatica`() {
        val text = "Crie um prompt para uma imagem fotorrealista, não pesquise"
        val intent = secretary.classify(text)
        val plan = DoorAwareSplitter().split(intent, text)

        assertEquals(Door.PROMPT, intent.door)
        assertTrue(intent.restrictions.contains(Restriction.NO_WEB))
        assertFalse(plan.any { it.capacidade == "network.research" })
        assertTrue(plan.any { it.capacidade == "prompt.library.write" })
    }

    @Test
    fun `follow up de melhoria explicitamente continua na Porta 2`() {
        val text = "Melhore este prompt para deixá-lo mais profissional"
        val intent = secretary.classify(text)

        assertEquals(Door.PROMPT, intent.door)
    }
}
