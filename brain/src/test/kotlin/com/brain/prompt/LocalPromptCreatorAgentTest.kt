package com.brain.prompt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalPromptCreatorAgentTest {
    private val agent = LocalPromptCreatorAgent()

    @Test fun `cria prompt de imagem sem API para o caso do foguete`() {
        val pedido = "Crie um prompt para uma fotografia fotorrealista de um foguete espacial decolando, " +
            "com iluminação cinematográfica, céu estrelado ao fundo, alta definição e detalhes realistas."
        val criado = agent.criar(pedido)

        assertEquals(PromptDomain.IMAGEM, criado.dominio)
        assertTrue("origem deve indicar criação local", criado.origem.startsWith("local:"))
        assertTrue("deve mencionar iluminação cinematográfica detectada", criado.texto.contains("cinematográfica"))
        assertTrue("deve conter estrutura de câmera/lente", criado.texto.contains("Câmera e lente"))
        assertFalse("não deve ser concatenação crua de palavras-chave (deve ter frases completas)", criado.texto.split(". ").size < 3)
    }

    @Test fun `nao duplica fotografia fotorrealista ao extrair sujeito`() {
        val pedido = "Crie um prompt para uma fotografia fotorrealista de um foguete espacial decolando, com iluminação cinematográfica."
        val texto = agent.criar(pedido).texto

        assertTrue(texto.startsWith("Fotografia fotorrealista de um foguete espacial decolando"))
        assertFalse(texto.contains("de fotografia fotorrealista de"))
    }

    @Test fun `preserva ambiente concreto ceu estrelado ao fundo`() {
        val pedido = "Crie um prompt para uma fotografia fotorrealista de um foguete espacial decolando, com céu estrelado ao fundo."
        val texto = agent.criar(pedido).texto

        assertTrue(texto.contains("céu estrelado ao fundo"))
        assertFalse(texto.contains("ambientado em fundo"))
    }

    @Test fun `sem componentes explicitos usa padroes deterministicos, nunca vazio`() {
        val a = agent.criar("Crie um prompt de uma xícara de café em cima de uma mesa")
        val b = agent.criar("Crie um prompt de uma xícara de café em cima de uma mesa")
        assertEquals("mesma entrada deve produzir mesma saída (determinístico)", a.texto, b.texto)
        assertTrue(a.texto.isNotBlank())
    }

    @Test fun `dominio texto usa estrutura objetivo e formato de saida`() {
        val criado = agent.criar("Escreva um prompt para gerar um resumo executivo de reunião")
        assertEquals(PromptDomain.TEXTO, criado.dominio)
        assertTrue(criado.texto.contains("OBJETIVO"))
    }

    @Test fun `melhorarLocalmente preenche componentes fracos sem inventar o pedido`() {
        val original = "Foto de um foguete."
        val melhorado = agent.melhorarLocalmente(
            promptAtual = original,
            pedidoOriginal = "Crie um prompt de um foguete decolando",
            pontosFracos = setOf("especificidade", "presença de elementos")
        )
        assertTrue(melhorado.texto.length > original.length)
        assertTrue(melhorado.texto.startsWith(original))
    }

    @Test fun `refinamento concreto troca fundo e adiciona meteoros sem API`() {
        val original = "Fotografia fotorrealista de um foguete espacial decolando, ambientado em céu estrelado ao fundo. Composição: equilibrada."
        val melhorado = agent.melhorarLocalmente(
            promptAtual = original,
            pedidoOriginal = "Agora quero o fundo no deserto e vários meteoros caindo",
            pontosFracos = emptySet()
        )

        assertTrue(melhorado.texto.contains("ambientado em deserto"))
        assertTrue(melhorado.texto.contains("Vários meteoros caindo"))
    }
}
