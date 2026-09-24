package com.brain.prompt

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptRequestChecklistTest {
    @Test fun `melhoria sem prompt anterior pede referencia antes de gerar`() {
        val resultado = PromptRequestChecklist.analisar(
            "vamos melhorar o prompt quero que o rosto da imagem seja igual sem mudar a fisionomia"
        )

        assertFalse(resultado.pronto)
        assertTrue(resultado.elementosFaltantes.contains("referência anterior"))
        assertTrue(resultado.perguntas.single().contains("prompt ou a imagem anterior"))
    }

    @Test fun `criacao de imagem sem sujeito pede sujeito principal`() {
        val resultado = PromptRequestChecklist.analisar("crie um prompt para uma imagem")

        assertFalse(resultado.pronto)
        assertTrue(resultado.elementosFaltantes.contains("sujeito principal"))
    }

    @Test fun `transformacao de foto com sujeito passa no checklist`() {
        val resultado = PromptRequestChecklist.analisar(
            "pegue minha foto e transforme minha imagem em um cavaleiro dos zodíacos de ouro"
        )

        assertTrue(resultado.pronto)
    }

    @Test fun `checklist detecta redundancia e preserva coerencia`() {
        val resultado = PromptRequestChecklist.validarGerado(
            "crie um prompt de um cavaleiro de ouro",
            "Cavaleiro de ouro com armadura dourada. Cavaleiro de ouro com armadura dourada."
        )

        assertTrue(resultado.coerente)
        assertTrue(resultado.avisos.contains("redundância detectada"))
    }
}
