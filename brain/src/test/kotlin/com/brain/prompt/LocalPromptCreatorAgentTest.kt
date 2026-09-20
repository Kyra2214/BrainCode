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

    // Regressão do follow-up "fotorrealista + deserto + visão de uma plataforma de longe":
    // o prompt ficava idêntico ("Ilustração digital..., cenário coerente com o assunto").
    @Test fun `refinamento aplica fotorrealista deserto e visao de longe sem API`() {
        val original = "Ilustração digital detalhada de um foguete decolando, ambientado em um cenário coerente com o assunto, " +
            "sem elementos que não foram pedidos. Composição: enquadramento equilibrado, assunto em destaque no terço de composição. " +
            "Iluminação: iluminação natural e equilibrada realçando volumes e texturas. " +
            "Nível de realismo: nível de realismo fotográfico. Qualidade: alta definição, sem artefatos visuais."
        val melhorado = agent.melhorarLocalmente(
            promptAtual = original,
            pedidoOriginal = "muda para fotorrealista no deserto com visão de uma plataforma de longe",
            pontosFracos = emptySet()
        )

        val texto = melhorado.texto.lowercase()
        assertTrue(texto.startsWith("fotografia fotorrealista"))
        assertTrue(texto.contains("ambientado em deserto"))
        assertTrue(texto.contains("visão de uma plataforma de longe"))
        assertFalse(texto.contains("ilustração digital"))
    }

    @Test fun `refinamento nao troca estilo quando o usuario nega fotorrealismo`() {
        val original = "Ilustração digital detalhada de um foguete decolando, ambientado em um cenário. Nível de realismo: estilizado."
        val melhorado = agent.melhorarLocalmente(original, "quero sem fotorrealismo no deserto", emptySet())
        assertTrue(melhorado.texto.startsWith("Ilustração digital"))
    }

    // Frase real digitada pelo usuário no app.
    @Test fun `refinamento aplica deserto por do sol e visao de plataforma de longe`() {
        val original = "Ilustração digital detalhada de um foguete decolando, ambientado em um cenário coerente com o assunto, " +
            "sem elementos que não foram pedidos. Composição: enquadramento equilibrado, assunto em destaque no terço de composição. " +
            "Iluminação: iluminação natural e equilibrada realçando volumes e texturas. Qualidade: alta definição."
        val melhorado = agent.melhorarLocalmente(
            promptAtual = original,
            pedidoOriginal = "vamos melhorar ele quero ele num deserto ao por do sol com a visão de uma plataforma de longe",
            pontosFracos = emptySet()
        )

        val texto = melhorado.texto
        assertTrue(texto.contains("ambientado em deserto ao pôr do sol"))
        assertTrue(texto.contains("luz dourada do pôr do sol"))
        assertTrue(texto.contains("visão de uma plataforma de longe"))
        // não pediu fotorrealismo: o estilo não muda
        assertTrue(texto.startsWith("Ilustração digital"))
    }

    @Test fun `pesquisa refina luz e lente somente com termos trazidos pelas fontes`() {
        val original = "Ilustração digital detalhada de um foguete decolando, ambientado em um cenário coerente. " +
            "Composição: equilibrada. Iluminação: natural. " +
            "Câmera e lente: câmera fotográfica padrão, lente com distância focal neutra (por volta de 50mm), profundidade de campo moderada."
        val pedido = "vamos melhorar ele quero ele num deserto ao por do sol com a visão de uma plataforma de longe"

        val comPesquisa = agent.melhorarLocalmente(
            original, pedido, emptySet(),
            "Fotógrafos usam golden hour para luz dourada; uma teleobjetiva comprime a distância entre plataforma e foguete."
        )
        assertTrue(comPesquisa.texto.contains("golden hour"))
        assertTrue(comPesquisa.texto.contains("teleobjetiva"))
        assertFalse(comPesquisa.texto.contains("distância focal neutra"))

        val semPesquisa = agent.melhorarLocalmente(original, pedido, emptySet(), null)
        assertFalse(semPesquisa.texto.contains("golden hour"))
        assertTrue(semPesquisa.texto.contains("distância focal neutra"))
    }
}
