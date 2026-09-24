package com.brain.skill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BuiltInSkillManifestTest {
    @Test
    fun `skills candidatas do plano sao materializadas mas nao registradas`() {
        val manifests = BuiltInSkillManifests.all()
        assertEquals(setOf("research", "github-research", "code-test", "android-build", "project-audit", "debug"), manifests.map { it.id }.toSet())
        assertTrue(manifests.all { it.trustLevel == TrustLevel.CORE && it.enabled })
    }
}
