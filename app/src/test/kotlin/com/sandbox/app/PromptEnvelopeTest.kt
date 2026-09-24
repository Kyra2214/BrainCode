package com.sandbox.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PromptEnvelopeTest {
    private val prompt = "Ilustração digital detalhada de um foguete decolando, ambientado em um cenário coerente. Qualidade: alta definição."

    @Test fun `remove o cabecalho de apresentacao`() {
        val resposta = "Encontrei um prompt de referência na biblioteca e adaptei ao seu pedido (estimativa heurística interna — qualidade 81%):\n\n$prompt"
        assertEquals(prompt, PromptEnvelope.extrairPrompt(resposta))
    }

    @Test fun `remove cabecalhos aninhados e a nota de IA indisponivel`() {
        val interno = "Encontrei um prompt de referência na biblioteca e adaptei ao seu pedido (estimativa heurística interna — qualidade 81%):\n\n$prompt"
        val resposta = "Melhorei o prompt com o Prompt Creator local (estimativa heurística interna — qualidade 78%):\n\n$interno" +
            "\n\n(Melhoria por IA não está disponível no momento — entreguei o melhor resultado local possível.)"
        val extraido = PromptEnvelope.extrairPrompt(resposta)
        assertEquals(prompt, extraido)
        assertFalse(extraido.contains("estimativa heurística"))
    }

    @Test fun `texto sem involucro permanece igual`() {
        assertEquals(prompt, PromptEnvelope.extrairPrompt(prompt))
    }
}
