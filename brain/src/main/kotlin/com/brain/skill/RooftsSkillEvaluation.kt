package com.brain.skill

data class RooftsSkillEvaluationCase(
    val id: String,
    val objective: String,
    val expectedSkillIds: Set<String> = emptySet(),
    val excludedSkillIds: Set<String> = emptySet()
)

data class RooftsSkillEvaluationResult(
    val caseId: String,
    val selectedSkillIds: Set<String>,
    val expectedSkillIds: Set<String>,
    val excludedSkillIds: Set<String>,
    val passed: Boolean,
    val reason: String
)

data class RooftsSkillEvaluationSummary(
    val results: List<RooftsSkillEvaluationResult>
) {
    val passed: Int get() = results.count { it.passed }
    val failed: Int get() = results.size - passed
    val score: Double get() = if (results.isEmpty()) 1.0 else passed.toDouble() / results.size
}

/** Avaliação local e reproduzível; não executa corpo de Skill nem provider. */
object RooftsSkillEvaluator {
    fun evaluate(
        skills: List<RooftsSkill>,
        cases: List<RooftsSkillEvaluationCase>,
        max: Int = RooftsSkillSelector.MAX_SKILLS
    ): RooftsSkillEvaluationSummary = RooftsSkillEvaluationSummary(cases.map { test ->
        val selected = RooftsSkillSelector.select(test.objective, skills, max).map { it.id }.toSet()
        val expectedPresent = test.expectedSkillIds.all { it in selected }
        val exclusionsRespected = test.excludedSkillIds.none { it in selected }
        RooftsSkillEvaluationResult(
            caseId = test.id,
            selectedSkillIds = selected,
            expectedSkillIds = test.expectedSkillIds,
            excludedSkillIds = test.excludedSkillIds,
            passed = expectedPresent && exclusionsRespected,
            reason = when {
                !expectedPresent -> "skill esperada não selecionada"
                !exclusionsRespected -> "skill excluída foi selecionada"
                else -> "seleção compatível"
            }
        )
    })
}
