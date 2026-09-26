package com.brain.skill

/** Estado da autorização do contexto adicional de uma Skill. */
enum class SkillApprovalStatus { NOT_REQUIRED, PENDING, APPROVED, DENIED }

data class RooftsSkillActivation(
    val runId: String,
    val skillId: String,
    val confidence: Int,
    val reason: String,
    val resourcesRequested: Set<String>,
    val permissionsRequested: Set<String>,
    val approvalStatus: SkillApprovalStatus
)

data class RooftsSkillActivationPlan(
    val runId: String,
    val activations: List<RooftsSkillActivation>
) {
    val approvedSkillIds: Set<String>
        get() = activations.filter { it.approvalStatus == SkillApprovalStatus.NOT_REQUIRED || it.approvalStatus == SkillApprovalStatus.APPROVED }
            .map { it.skillId }
            .toSet()

    val requestedPermissions: Set<String>
        get() = activations.flatMap { it.permissionsRequested }.toSet()
}

/**
 * Planeja somente contexto metodológico. A aprovação de permissões continua fora deste planner.
 * Sem uma função explícita de aprovação, qualquer Skill que peça acesso permanece pendente.
 */
object RooftsSkillActivationPlanner {
    fun plan(
        runId: String,
        objective: String,
        skills: List<RooftsSkill>,
        max: Int = RooftsSkillSelector.MAX_SKILLS,
        permissionApproval: (Set<String>) -> Boolean = { it.isEmpty() },
        historicalSuccessRate: (String) -> Double = { 0.5 }
    ): RooftsSkillActivationPlan {
        val selected = RooftsSkillSelector.select(objective, skills, max)
        return RooftsSkillActivationPlan(
            runId = runId,
            activations = selected.map { skill ->
                val permissions = skill.requiredPermissions
                val approved = permissionApproval(permissions)
                RooftsSkillActivation(
                    runId = runId,
                    skillId = skill.id,
                    confidence = confidence(objective, skill, historicalSuccessRate("skill:${skill.id}")),
                    reason = if (skill.triggers.isEmpty()) "description/default selector" else "declared trigger or selector signal",
                    resourcesRequested = skill.resources,
                    permissionsRequested = permissions,
                    approvalStatus = when {
                        permissions.isEmpty() -> SkillApprovalStatus.NOT_REQUIRED
                        approved -> SkillApprovalStatus.APPROVED
                        else -> SkillApprovalStatus.PENDING
                    }
                )
            }
        )
    }

    internal fun confidence(objective: String, skill: RooftsSkill, historicalSuccessRate: Double = 0.5): Int {
        val trigger = skill.triggers.any { objective.contains(it, ignoreCase = true) }
        val description = skill.description.split(Regex("\\s+")).count { word ->
            word.length >= 4 && objective.contains(word.trim('.', ',', ':'), ignoreCase = true)
        }
        val lexical = (if (trigger) 80 else 50) + minOf(description * 5, 20)
        val historical = historicalSuccessRate.coerceIn(0.0, 1.0)
        return (lexical + ((historical - 0.5) * 20.0).toInt()).coerceIn(0, 100)
    }
}
