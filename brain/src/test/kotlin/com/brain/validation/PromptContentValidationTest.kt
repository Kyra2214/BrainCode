package com.brain.validation

import org.junit.Assert.assertEquals
import org.junit.Test

class PromptContentValidationTest {
    @Test
    fun `prompt content valida requisitos semanticos`() {
        val result = ValidationEngine().promptContent(
            ValidationSubject(
                capability = "prompt.library.generate",
                requirements = listOf("foguete", "deserto", "por do sol"),
                result = "Fotorrealista: foguete decolando no deserto ao pôr do sol.",
                evidence = listOf("prompt:generated")
            )
        )
        assertEquals(ValidationStatus.PASS, result.status)
    }

    @Test
    fun `prompt content valida slots estruturados quando declarados`() {
        val result = ValidationEngine().promptContent(
            ValidationSubject(
                capability = "prompt.library.generate",
                requirements = listOf(
                    "sujeito: foguete",
                    "ação: decolando",
                    "ambiente: deserto",
                    "iluminação: pôr do sol",
                    "formato: vertical"
                ),
                result = "Foguete decolando no deserto ao pôr do sol, composição vertical.",
                evidence = listOf("prompt:generated")
            )
        )
        assertEquals(ValidationStatus.PASS, result.status)
    }

    @Test
    fun `prompt content reprova slot estruturado ausente`() {
        val result = ValidationEngine().promptContent(
            ValidationSubject(
                capability = "prompt.library.generate",
                requirements = listOf("sujeito: foguete", "ambiente: deserto"),
                result = "Cena cinematográfica no deserto.",
                evidence = listOf("prompt:generated")
            )
        )
        assertEquals(ValidationStatus.FAIL, result.status)
        assertEquals(true, result.failedChecks.any { it == "prompt.slot.subject" })
    }
}
