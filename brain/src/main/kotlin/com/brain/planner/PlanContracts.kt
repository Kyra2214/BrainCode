package com.brain.planner

/** Intenção normalizada antes da decomposição; não contém executor. */
data class Intent(
    val objective: String,
    val assumptions: Set<String> = emptySet(),
    val constraints: Set<String> = emptySet()
) {
    init { require(objective.isNotBlank()) { "objetivo da intenção é obrigatório" } }
}

data class Dependency(val taskId: String, val dependsOn: String) {
    init {
        require(taskId.isNotBlank() && dependsOn.isNotBlank()) { "dependência exige ids" }
        require(taskId != dependsOn) { "tarefa não pode depender de si mesma" }
    }
}

data class Task(
    val id: String,
    val objective: String,
    val inputs: List<String> = emptyList(),
    val outputs: List<String> = emptyList(),
    val dependencies: Set<String> = emptySet(),
    val capabilities: Set<String> = emptySet(),
    val agent: String? = null,
    val retryLimit: Int = 0,
    val validation: String = "success",
    val acceptanceCriteria: List<com.brain.behavior.AcceptanceCriteria> = emptyList()
) {
    init {
        require(id.isNotBlank() && objective.isNotBlank()) { "Task exige id e objetivo" }
        require(retryLimit >= 0) { "retryLimit não pode ser negativo" }
        require(capabilities.none { it.isBlank() }) { "capability de Task não pode ser vazia" }
    }
}
