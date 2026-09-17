package com.brain.memory

import com.brain.research.ResearchResult
import java.time.Instant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LayeredMemoryTest {
    @Test
    fun `evidencia nasce nao validada e pode ser validada por url`() {
        val memory = LayeredMemory()
        val result = ResearchResult("q", "web", "Título", "https://example.com", "conteúdo", Instant.now(), confidence = 0.7)
        memory.rememberEvidence(result, Provenance("web-search", confidence = 0.7))

        assertFalse(memory.evidences().single().validated)
        assertTrue(memory.validateEvidence("https://example.com"))
        assertTrue(memory.evidences().single().validated)
    }
}
