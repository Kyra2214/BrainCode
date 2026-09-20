package com.brain.planner

import com.brain.secretary.CreatePhase
import com.brain.secretary.DeterministicSecretary
import com.brain.secretary.Door
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatDoorLeakCorpusTest {
    private val secretary = DeterministicSecretary()

    @Test
    fun `corpus de chat nunca produz workspace ou sandbox code`() {
        val corpus = listOf(
            "Estou pensando em criar um aplicativo, ainda não decidi nada",
            "Não pesquise e não crie nada agora, só quero conversar",
            "Quero organizar os requisitos sem executar nada",
            "Estou em dúvida sobre a arquitetura do meu projeto",
            "Sem criar projeto: quais decisões ainda faltam?"
        )

        corpus.forEach { prompt ->
            val intent = secretary.classify(prompt)
            val plan = DoorAwareSplitter().split(intent, prompt)
            assertEquals(prompt, Door.CHAT, intent.door)
            assertEquals(prompt, CreatePhase.CHAT, intent.phase)
            assertFalse(prompt, plan.any { it.capacidade == "workspace.write" || it.capacidade == "workspace.generate" })
            assertFalse(prompt, plan.any { it.capacidade == "sandbox.code" || it.capacidade == "sandbox.test" })
            assertTrue(prompt, plan.any { it.capacidade == "chat.respond" })
        }
    }

    @Test
    fun `pesquisa explicitamente permitida fica antes de chat respond`() {
        val prompt = "Pesquise fontes atuais sobre memória e me explique o resumo"
        val intent = secretary.classify(prompt)
        val plan = DoorAwareSplitter().split(intent, prompt)

        assertEquals(Door.CHAT, intent.door)
        assertTrue(plan.map { it.capacidade }.containsAll(listOf("network.research", "chat.respond")))
        assertTrue(plan.first { it.capacidade == "chat.respond" }.dependeDe.contains("pesquisar"))
    }
}
