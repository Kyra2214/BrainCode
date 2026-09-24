package com.brain.skill

/** Resultado de uma avaliação semântica local, sem provider ou credencial externa. */
data class RooftsSemanticGrade(
    val caseId: String,
    val score: Double,
    val passed: Boolean,
    val selectedSkillIds: Set<String>,
    val rationale: String
)

data class RooftsSemanticGradeSummary(val grades: List<RooftsSemanticGrade>, val minimumScore: Double) {
    val passed: Boolean get() = grades.all { it.passed }
    val averageScore: Double get() = if (grades.isEmpty()) 1.0 else grades.map { it.score }.average()
}

/**
 * Grader opcional de CI. É chamado de semântico porque considera objetivo, descrição,
 * triggers, exclusões e corpo; permanece offline e determinístico para não introduzir
 * custo, rede ou uma dependência de provider no aplicativo.
 */
object RooftsSemanticGrader {
    fun grade(
        skills: List<RooftsSkill>,
        cases: List<RooftsSkillEvaluationCase>,
        minimumScore: Double = 0.80,
        max: Int = RooftsSkillSelector.MAX_SKILLS
    ): RooftsSemanticGradeSummary {
        require(minimumScore in 0.0..1.0) { "minimumScore inválido" }
        val grades = cases.map { test ->
            val selected = RooftsSkillSelector.select(test.objective, skills, max)
            val selectedIds = selected.map { it.id }.toSet()
            val expected = if (test.expectedSkillIds.isEmpty()) 1.0 else
                test.expectedSkillIds.count { it in selectedIds }.toDouble() / test.expectedSkillIds.size
            val excluded = if (test.excludedSkillIds.isEmpty()) 1.0 else
                test.excludedSkillIds.count { it !in selectedIds }.toDouble() / test.excludedSkillIds.size
            val objectiveTokens = tokens(test.objective)
            val relevance = if (selected.isEmpty() || objectiveTokens.isEmpty()) 0.0 else
                selected.map { skill ->
                    val searchable = tokens(listOf(skill.id, skill.description, skill.body, skill.triggers.joinToString(" ")).joinToString(" "))
                    objectiveTokens.count { it in searchable }.toDouble() / objectiveTokens.size
                }.maxOrNull()?.coerceIn(0.0, 1.0) ?: 0.0
            val score = (expected * 0.55 + excluded * 0.25 + relevance * 0.20).coerceIn(0.0, 1.0)
            RooftsSemanticGrade(test.id, score, score >= minimumScore, selectedIds,
                "expected=${"%.2f".format(expected)} excluded=${"%.2f".format(excluded)} relevance=${"%.2f".format(relevance)}")
        }
        return RooftsSemanticGradeSummary(grades, minimumScore)
    }

    private fun tokens(value: String): Set<String> = value.lowercase()
        .replace(Regex("[^\\p{L}\\p{N}_-]+"), " ")
        .split(Regex("\\s+"))
        .filter { it.length >= 3 }
        .toSet()
}
