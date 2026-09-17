package com.brain.reasoning

import com.brain.execution.OperationalState
import com.brain.memory.MemoryEntry
import com.brain.research.ResearchResult

data class TaskState(
    val objective: String,
    val reasoning: ReasoningState? = null,
    val operational: OperationalState? = null,
    val evidence: List<MemoryEntry<ResearchResult>> = emptyList(),
    val critique: CritiqueResult? = null,
    val revisions: Int = 0
) {
    init { require(objective.isNotBlank()) { "TaskState exige objetivo" } }

    val requirements: List<Requirement> get() = reasoning?.requirements.orEmpty()
    val assumptions: List<String> get() = reasoning?.assumptions.orEmpty()
    val missingRequirements: List<String> get() = reasoning?.missing.orEmpty()
    val readyForExecution: Boolean get() = reasoning != null && missingRequirements.isEmpty()

    fun withReasoning(value: ReasoningState): TaskState = copy(reasoning = value)
    fun withOperational(value: OperationalState): TaskState = copy(operational = value)
    fun withCritique(value: CritiqueResult, revisionCount: Int = revisions): TaskState = copy(critique = value, revisions = revisionCount)
    fun withEvidence(value: List<MemoryEntry<ResearchResult>>): TaskState = copy(evidence = value)
}
