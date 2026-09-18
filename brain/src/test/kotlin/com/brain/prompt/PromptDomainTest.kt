package com.brain.prompt

import org.junit.Assert.assertEquals
import org.junit.Test

class PromptDomainTest {
    @Test fun `pedido de prompt para interface de app e codigo`() {
        assertEquals(
            PromptDomain.CODIGO,
            PromptDomain.classificar("crie um prompt pro meu app chatbox quero a interface idêntica à do chatgpt")
        )
    }

    @Test fun `prompt de imagem continua sendo imagem`() {
        assertEquals(
            PromptDomain.IMAGEM,
            PromptDomain.classificar("crie um prompt para uma fotografia fotorrealista de um foguete")
        )
    }

    @Test fun `prompt para criar uma imagem e imagem mesmo sem palavra fotografia`() {
        assertEquals(
            PromptDomain.IMAGEM,
            PromptDomain.classificar("quero um prompt para criar uma imagem de um foguete decolando em um deserto no entardecer")
        )
    }

    @Test fun `prompt para gerar imagem com sujeito visual e imagem`() {
        assertEquals(
            PromptDomain.IMAGEM,
            PromptDomain.classificar("gere um prompt para gerar uma imagem de um foguete com muitos detalhes")
        )
    }

    @Test fun `texto generico nao captura ata dentro de plataforma`() {
        assertEquals(
            PromptDomain.IMAGEM,
            PromptDomain.classificar("crie um prompt de um mascote para uma plataforma de software")
        )
    }
}
