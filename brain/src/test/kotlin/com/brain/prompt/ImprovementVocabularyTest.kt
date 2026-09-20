package com.brain.prompt

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImprovementVocabularyTest {
    @Test fun `gatilhos que acionam a IA`() {
        listOf(
            "melhore ele", "vamos melhorar ele quero ele num deserto", "faça melhor", "faz melhor isso",
            "refaça", "refazer o prompt", "capriche", "aprimore ele", "reescreva", "otimize", "de novo",
            "outra versão", "quero mais detalhado", "Melhore Ele"
        ).forEach { assertTrue("deveria acionar IA: $it", ImprovementVocabulary.pedeIA(it)) }
    }

    @Test fun `ajuste simples nao aciona a IA`() {
        listOf("quero ele num deserto ao por do sol", "coloca uma plataforma ao longe", "muda o fundo para noite")
            .forEach { assertFalse("não deveria acionar IA: $it", ImprovementVocabulary.pedeIA(it)) }
    }

    @Test fun `novos verbos sao reconhecidos como pedido de ajuste`() {
        listOf("refaça ele", "aprimore isso", "capriche nele", "reescreva o prompt").forEach {
            assertTrue(it, ImprovementVocabulary.containsIn(it))
        }
    }
}
