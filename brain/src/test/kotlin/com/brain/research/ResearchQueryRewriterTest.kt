package com.brain.research

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResearchQueryRewriterTest {

    @Test
    fun `nao aplica vies de arquitetura tecnica para perguntas casuais com como`() {
        // Bug real: "como está" virava uma busca por arquitetura de computadores
        // porque qualquer frase começando com "como " ganhava o sufixo técnico.
        val casuais = listOf("como está", "como você está", "como vai", "como faço isso", "como chegar na estação")
        casuais.forEach { query ->
            val rewritten = ResearchQueryRewriter.rewrite(query)
            assertFalse(
                "não deveria adicionar viés técnico a '$query', mas gerou: $rewritten",
                rewritten.contains("arquitetura, componentes e funcionamento técnico")
            )
        }
    }

    @Test
    fun `mantem vies tecnico apenas quando a pergunta e sobre como algo funciona`() {
        val tecnicas = listOf("como funciona um motor a diesel", "como o wifi funciona", "como isso funciona")
        tecnicas.forEach { query ->
            val rewritten = ResearchQueryRewriter.rewrite(query)
            assertTrue(
                "deveria adicionar viés técnico a '$query', mas gerou: $rewritten",
                rewritten.contains("arquitetura, componentes e funcionamento técnico")
            )
        }
    }
}
