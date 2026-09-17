package com.brain.reasoning

import com.brain.prompt.PromptDomain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RequirementDiscoveryTest {
    @Test
    fun `descoberta produz slots e dependencias para sujeito estilo e elementos`() {
        val result = RequirementDiscovery().discover(
            "crie uma fotografia fotorrealista de um foguete com céu estrelado ao fundo e adicione meteoros caindo",
            PromptDomain.IMAGEM
        )

        assertTrue(result.slots.any { it.name == "sujeito" })
        assertTrue(result.slots.any { it.name == "estilo" })
        assertTrue(result.dependencies.any { it.requirement == "elementos" && it.dependsOn == "sujeito" })
        assertEquals(emptyList<String>(), result.missing)
    }
}
