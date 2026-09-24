package com.brain.planning

import com.brain.reasoning.ReasoningEngine
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanningAgentTest {
    @Test
    fun `materializa ideia requisitos decisoes pendencias e referencias`() {
        val state = ReasoningEngine().analyze("Quero um app de notas offline")
        val artifact = PlanningAgent().plan("run-plan", state, listOf("docs:notes", "web:offline"))
        assertEquals("Quero um app de notas offline", artifact.idea)
        assertTrue(artifact.decisions.any { it.startsWith("intent=") })
        assertTrue(artifact.references.contains("docs:notes"))
        assertTrue(artifact.status == PlanningStatus.READY || artifact.status == PlanningStatus.NEEDS_CLARIFICATION)

        val file = Files.createTempFile("planning-", ".jsonl").toFile()
        try {
            FilePlanningArtifactStore(file).save(artifact)
            assertEquals(artifact.artifactId, FilePlanningArtifactStore(file).get("run-plan")!!.artifactId)
        } finally { file.delete() }
    }
}
