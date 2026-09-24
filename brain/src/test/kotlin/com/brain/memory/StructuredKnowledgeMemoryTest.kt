package com.brain.memory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class StructuredKnowledgeMemoryTest {
    @Test
    fun `parafrase com intent e entidade equivalente bate na camada um`() {
        val memory = InMemoryKnowledgeMemory()
        val entry = memory.saveCandidate(
            KnowledgeEntry(
                problem = "qual a capital da Rússia?",
                answer = "Moscou.",
                source = null,
                validated = true,
                intent = "FACTUAL_QUESTION",
                normalizedQuery = "qual a capital da russia",
                entities = mapOf("pais" to "Rússia")
            )
        )

        val hit = memory.findValidatedStructured(
            problem = "qual é a capital russa?",
            intent = "FACTUAL_QUESTION",
            entities = mapOf("pais" to "russa")
        )

        assertNotNull(hit)
        assertEquals(entry.id, hit.id)
    }

    @Test
    fun `intent diferente nao gera falso positivo estruturado`() {
        val memory = InMemoryKnowledgeMemory()
        memory.saveCandidate(
            KnowledgeEntry(
                problem = "qual a capital da Rússia?",
                answer = "Moscou.",
                source = null,
                validated = true,
                intent = "FACTUAL_QUESTION",
                entities = mapOf("pais" to "Rússia")
            )
        )

        assertNull(
            memory.findValidatedStructured(
                problem = "qual a temperatura em Macaé?",
                intent = "WEATHER",
                entities = mapOf("cidade" to "Macaé"),
                minScore = 0.99
            )
        )
    }

    @Test
    fun `correcao marca status corrected e incrementa versao`() {
        val memory = InMemoryKnowledgeMemory()
        val entry = memory.saveCandidate(
            KnowledgeEntry(problem = "pergunta", answer = "A", source = null, validated = true)
        )

        val corrected = memory.recordCorrection(entry.id, "B", 0.9)!!

        assertEquals(ValidationStatus.CORRECTED, corrected.validationStatus)
        assertEquals(2, corrected.version)
    }
}
