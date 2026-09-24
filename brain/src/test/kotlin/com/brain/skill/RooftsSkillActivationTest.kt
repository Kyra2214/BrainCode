package com.brain.skill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RooftsSkillActivationTest {
    @Test
    fun `skill sem permissao fica disponivel e registra plano`() {
        val skill = RooftsSkill(
            id = "safe-review",
            description = "Review code changes.",
            body = "body",
            triggers = setOf("review"),
            resources = setOf("references/checklist.md")
        )

        val plan = RooftsSkillActivationPlanner.plan("run-1", "review this code", listOf(skill))

        assertEquals(setOf("safe-review"), plan.approvedSkillIds)
        assertEquals(SkillApprovalStatus.NOT_REQUIRED, plan.activations.single().approvalStatus)
        assertEquals(setOf("references/checklist.md"), plan.activations.single().resourcesRequested)
    }

    @Test
    fun `permissao declarada fica pendente sem aprovacao explicita`() {
        val skill = RooftsSkill(
            id = "network-review",
            description = "Review remote service.",
            body = "body",
            triggers = setOf("remote"),
            requiredPermissions = setOf("network")
        )

        val plan = RooftsSkillActivationPlanner.plan("run-2", "review remote service", listOf(skill))

        assertTrue(plan.approvedSkillIds.isEmpty())
        assertEquals(SkillApprovalStatus.PENDING, plan.activations.single().approvalStatus)
        assertEquals(setOf("network"), plan.requestedPermissions)
    }

    @Test
    fun `avaliador compara casos sem executar corpo`() {
        val skill = RooftsSkill(
            id = "safe-review",
            description = "Review code changes.",
            body = "not executed",
            triggers = setOf("review")
        )

        val summary = RooftsSkillEvaluator.evaluate(
            listOf(skill),
            listOf(RooftsSkillEvaluationCase("case-1", "review this code", expectedSkillIds = setOf("safe-review")))
        )

        assertEquals(1, summary.passed)
        assertEquals(1.0, summary.score, 0.0)
    }
}
