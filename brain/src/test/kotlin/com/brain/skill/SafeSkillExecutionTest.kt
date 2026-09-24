package com.brain.skill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class SafeSkillExecutionTest {
    @Test
    fun `grader semantico e offline produz score reproduzivel`() {
        val skill = RooftsSkill("planning", "planejamento e tarefas", "planejar tarefas com etapas", triggers = setOf("planejar"))
        val result = RooftsSemanticGrader.grade(listOf(skill), listOf(RooftsSkillEvaluationCase("p", "planejar tarefas", setOf("planning"))), 0.5)
        assertTrue(result.passed)
        assertEquals(result.averageScore, RooftsSemanticGrader.grade(listOf(skill), listOf(RooftsSkillEvaluationCase("p", "planejar tarefas", setOf("planning"))), 0.5).averageScore, 0.0)
    }

    @Test
    fun `executor exige allowlist e executa sem shell`() {
        val root = Files.createTempDirectory("safe-skill").toFile()
        try {
            val result = SafeSkillResourceExecutor(setOf("/bin/printf")) { request ->
                SafeSkillExecutionResult(0, request.arguments.joinToString(""), "", false)
            }.execute(
                SafeSkillExecutionRequest("/bin/printf", listOf("ok"), root)
            )
            assertEquals(0, result.exitCode)
            assertEquals("ok", result.stdout)
            val denied = runCatching {
                SafeSkillResourceExecutor(emptySet()) { SafeSkillExecutionResult(0, "", "", false) }
                    .execute(SafeSkillExecutionRequest("/bin/printf", listOf("x"), root))
            }.exceptionOrNull()
            assertTrue(denied is IllegalArgumentException)
        } finally { root.deleteRecursively() }
    }
}
