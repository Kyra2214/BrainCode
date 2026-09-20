package com.brain.behavior

data class ClarificationQuestion(
    val originalObjective: String,
    val missingRequirements: List<String>,
    val question: String
) {
    init {
        require(originalObjective.isNotBlank())
        require(missingRequirements.isNotEmpty())
        require(question.isNotBlank())
    }

    companion object {
        fun from(objective: String, issues: List<GateIssue>): ClarificationQuestion {
            val missing = issues.filter { it.code == "missing.requirement" }.map { it.message }.distinct()
            require(missing.isNotEmpty()) { "clarification exige requisitos ausentes" }
            val question = buildString {
                append("Para continuar, preciso esclarecer: ")
                append(missing.joinToString("; "))
                append(". Qual é a sua preferência?")
            }
            return ClarificationQuestion(objective, missing, question)
        }
    }
}
