package com.brain.capability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BuiltInSpecialistsTest {
    @Test
    fun `declara todos os especialistas previstos sem provider proprio`() {
        val specialists = BuiltInAgentDefinitions.boundedSpecialists()
        val ids = specialists.map { it.id }

        assertEquals(11, specialists.size)
        assertTrue(ids.containsAll(listOf(
            "agent.requirements", "agent.architecture", "agent.roadmap", "agent.ui", "agent.backend",
            "agent.database", "agent.security", "agent.test", "agent.review", "agent.integration", "agent.release"
        )))
        assertTrue(specialists.all { it.category == CapabilityCategory.AGENT && it.provenance.isNotEmpty() })
    }
}
