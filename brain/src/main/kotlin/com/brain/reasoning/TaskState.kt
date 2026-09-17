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
    val revisions: Int = 0,
    val planSummary: String? = null,
    val cycleSummary: String? = null,
    val plannerAssumptions: List<String> = emptyList()
) {
    init { require(objective.isNotBlank()) { "TaskState exige objetivo" } }

    val requirements: List<Requirement> get() = reasoning?.requirements.orEmpty()
    val assumptions: List<String> get() = reasoning?.assumptions.orEmpty()
    val missingRequirements: List<String> get() = reasoning?.missing.orEmpty()
    val readyForExecution: Boolean get() = reasoning != null && missingRequirements.isEmpty()
    val slots: List<RequirementSlot> get() = reasoning?.slots.orEmpty()
    val constraints: List<RequirementConstraint> get() = reasoning?.constraints.orEmpty()
    val dependencies: List<RequirementDependency> get() = reasoning?.dependencies.orEmpty()

    fun withReasoning(value: ReasoningState): TaskState = copy(reasoning = value)
    fun withOperational(value: OperationalState): TaskState = copy(operational = value)
    fun withCritique(value: CritiqueResult, revisionCount: Int = revisions): TaskState = copy(critique = value, revisions = revisionCount)
    fun withEvidence(value: List<MemoryEntry<ResearchResult>>): TaskState = copy(evidence = value)
    fun withPlan(summary: String, assumptions: List<String>): TaskState = copy(planSummary = summary, plannerAssumptions = assumptions)
    fun withCycle(summary: String): TaskState = copy(cycleSummary = summary)
}
