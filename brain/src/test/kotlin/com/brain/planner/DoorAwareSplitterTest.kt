package com.brain.planner

import com.brain.secretary.DeterministicSecretary
import com.brain.secretary.Door
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DoorAwareSplitterTest {
    private val splitter = DoorAwareSplitter(DeterministicSecretary())

    @Test
    fun `chat sobre aplicativo permanece conversa sem workspace ou sandbox`() {
        val steps = splitter.split("Estou pensando em criar um aplicativo; vamos discutir a arquitetura")

        assertTrue(steps.isNotEmpty())
        assertFalse(steps.any { it.capacidade == "workspace.write" || it.capacidade == "sandbox.code" })
    }

    @Test
    fun `prompt visual preserva pesquisa e geracao de prompt`() {
        val steps = splitter.split("Crie um prompt para uma imagem de um carro futurista")

        assertTrue(steps.any { it.capacidade == "prompt.library.write" })
        assertTrue(steps.any { it.capacidade == "network.research" })
        assertFalse(steps.any { it.capacidade == "workspace.write" })
    }

    @Test
    fun `criacao em discussao nao libera execucao`() {
        val intent = DeterministicSecretary().classify("Quero criar um aplicativo")
        val steps = splitter.split(intent, intent.originalPrompt)

        assertTrue(intent.door == Door.CREATE)
        assertFalse(steps.any { it.capacidade == "workspace.write" || it.capacidade == "sandbox.code" })
    }

    @Test
    fun `restricao no web remove pesquisa`() {
        val steps = splitter.split("Crie um prompt para uma imagem, mas não pesquise referências")

        assertFalse(steps.any { it.capacidade == "network.research" })
    }
}
