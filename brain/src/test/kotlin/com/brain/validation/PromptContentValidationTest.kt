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
}
