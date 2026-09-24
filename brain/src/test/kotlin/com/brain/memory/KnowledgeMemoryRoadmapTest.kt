package com.brain.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class KnowledgeMemoryRoadmapTest {
    @Test fun `deduplica evidencias e preserva citacao estruturada`() {
        val memory = InMemoryKnowledgeMemory()
        val citation = KnowledgeCitation("https://example.test/doc", "trecho verificavel")
        val first = memory.saveCandidate(KnowledgeEntry(problem = "como testar", answer = "rode testes", source = KnowledgeSource("web", citation.uri), citations = listOf(citation)))
        val duplicate = memory.saveCandidate(KnowledgeEntry(problem = "como testar", answer = "rode testes", source = KnowledgeSource("web", citation.uri), citations = listOf(citation)))
        assertEquals(first.id, duplicate.id)
        assertEquals(citation, memory.all().single().citations.single())
    }

    @Test fun `escopos nao vazam e correcao cria historico`() {
        val memory = InMemoryKnowledgeMemory()
        val user = memory.saveCandidate(KnowledgeEntry(problem = "projeto", answer = "A", source = null, scope = KnowledgeScope.USER, ownerId = "u1"))
        memory.confirm(user.id, 0.9)
        assertNotNull(memory.findValidated("projeto", scope = KnowledgeScope.USER, ownerId = "u1"))
        assertNull(memory.findValidated("projeto", scope = KnowledgeScope.USER, ownerId = "u2"))
        val corrected = memory.recordCorrection(user.id, "B", 0.95)!!
        assertEquals(1, corrected.correctionHistory.size)
        assertEquals(fingerprintFor("projeto", "B"), corrected.fingerprint)
    }
}
