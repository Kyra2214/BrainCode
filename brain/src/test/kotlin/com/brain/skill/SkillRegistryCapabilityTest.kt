package com.brain.skill

import com.brain.capability.CapabilityCategory
import com.brain.capability.CapabilityRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillRegistryCapabilityTest {
    @Test
    fun `skill validada publica procedimento no registry universal`() {
        val skills = SkillRegistry()
        skills.register(
            SkillManifest(
                id = "code.analysis",
                name = "Code Analysis",
                version = "1.0.0",
                description = "analisa código",
                category = "development",
                capabilities = setOf("code_analysis"),
                trustLevel = TrustLevel.VERIFIED,
                sourceId = "builtin"
            )
        )
        val registry = CapabilityRegistry()

        skills.publishTo(registry)

        val published = registry.findByCapability("code_analysis").single()
        assertEquals("skill.code.analysis", published.id)
        assertEquals(CapabilityCategory.SKILL, published.category)
        assertEquals(.9, published.reliability, 0.001)
        assertTrue(published.isDiscoverable())
    }
}
