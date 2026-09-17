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
}
