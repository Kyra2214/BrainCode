package com.brain.planner

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResearchQueryTest {
    @Test fun `follow-up usa assunto anterior e pedido atual sem palavras de conversa`() {
        val objetivo = "Objetivo atual: vamos melhorar ele quero ele num deserto ao por do sol com a visão de uma plataforma de longe\n" +
            "Referências resolvidas:\n- tipo da referência: prompt\n- artefato anterior: Ilustração digital detalhada de um foguete decolando, ambientado em um cenário."
        val q = ResearchQuery.paraPromptVisual(objetivo)

        listOf("foguete", "decolando", "deserto", "plataforma", "longe", "fotografia", "iluminação").forEach { assertTrue("$q deveria conter $it", it in q) }
        listOf("vamos", "melhorar", "quero", "prompt").forEach { assertFalse("$q não deveria conter $it", it in q.split(" ")) }
    }

    @Test fun `pedido novo de imagem vira consulta tecnica`() {
        val q = ResearchQuery.paraPromptVisual("crie um prompt de um foguete decolando")
        assertTrue(q.startsWith("foguete decolando"))
        assertTrue("composição" in q)
    }
}
