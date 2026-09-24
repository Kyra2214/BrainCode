package com.brain.memory

import com.brain.skill.SkillManifest
import com.brain.skill.SkillRegistry
import com.brain.skill.TrustLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoSkillsTest {
    private fun execution(id: String, success: Boolean = true, evidence: List<String> = listOf("log:$id")) = SuccessfulProcedure(
        executionId = id,
        objective = "gerar relatório",
        capability = "report.generate",
        success = success,
        evidence = evidence,
        provenance = listOf("run:$id")
    )

    private fun manifest(key: String, capability: String) = SkillManifest(
        id = "auto.${key.hashCode().toUInt().toString(16)}",
        name = "Auto candidate",
        version = "1.0.0",
        description = "procedimento repetido",
        category = "auto",
        capabilities = setOf(capability),
        trustLevel = TrustLevel.CORE,
        sourceId = "builtin"
    )

    @Test
    fun `detector gera proposta somente apos sucessos repetidos com evidencia`() {
        val detector = AutoSkillDetector()
        val proposals = detector.detect(
            listOf(execution("1"), execution("2"), execution("failed", success = false)),
            ::manifest
        )

        assertEquals(1, proposals.size)
        assertEquals(listOf("1", "2"), proposals.single().evidenceIds)
        assertEquals(listOf("run:1", "run:2"), proposals.single().provenance)
    }

    @Test
    fun `falhas e execucoes sem evidencia nao viram proposta`() {
        val proposals = AutoSkillDetector().detect(
            listOf(execution("1", success = false), execution("2", evidence = emptyList())),
            ::manifest
        )

        assertTrue(proposals.isEmpty())
    }

    @Test
    fun `proposta nao e registrada automaticamente`() {
        val registry = SkillRegistry()
        val proposals = AutoSkillDetector().detect(listOf(execution("1"), execution("2")), ::manifest)

        assertEquals(1, proposals.size)
        assertTrue(registry.get(proposals.single().manifest.id) == null)
        registry.register(proposals.single().manifest)
        assertTrue(registry.isUsable(proposals.single().manifest.id))
    }
}
