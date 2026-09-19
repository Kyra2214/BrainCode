package com.brain.behavior

import com.brain.planner.PlanoExecucao
import com.brain.reasoning.ReasoningState

class RequirementGate : BehaviorGate<ReasoningState, ReasoningState> {
    override fun evaluate(input: ReasoningState): GateResult<ReasoningState> {
        if (input.missing.isNotEmpty()) return GateResult(
            GateStatus.NEEDS_CLARIFICATION,
            input,
            input.missing.map { GateIssue("missing.requirement", it, blocking = true) }
        )
        return GateResult(GateStatus.READY, input)
    }
}

class PlanningGate : BehaviorGate<PlanoExecucao, PlanoExecucao> {
    override fun evaluate(input: PlanoExecucao): GateResult<PlanoExecucao> {
        val issues = input.passos.flatMap { step ->
            when {
                step.acceptanceCriteria.isEmpty() -> listOf(GateIssue("missing.acceptance", "passo ${step.id} não possui critérios", true))
                else -> step.acceptanceCriteria.flatMap { criterion ->
                    if (criterion.verification.isNullOrBlank()) {
                        listOf(GateIssue("missing.verification", "critério ${criterion.id} do passo ${step.id} não possui método de verificação", true))
                    } else emptyList()
                }
            }
        }
        return if (issues.isEmpty()) GateResult(GateStatus.PASSED, input)
        else GateResult(GateStatus.BLOCKED, input, issues)
    }
}

class VerificationGate : BehaviorGate<VerificationResult, VerificationResult> {
    override fun evaluate(input: VerificationResult): GateResult<VerificationResult> =
        if (input.passed) GateResult(GateStatus.PASSED, input)
        else GateResult(GateStatus.FAILED, input, listOf(GateIssue("verification.failed", "um ou mais critérios não passaram")))
}

class CriticGate : BehaviorGate<CritiqueResult, CritiqueResult> {
    override fun evaluate(input: CritiqueResult): GateResult<CritiqueResult> = when (input.status) {
        CritiqueStatus.PASS -> GateResult(GateStatus.PASSED, input)
        CritiqueStatus.NEEDS_REVISION -> GateResult(GateStatus.FAILED, input, input.findings.map { GateIssue(it.code, it.message, it.severity == FindingSeverity.BLOCKING) })
        CritiqueStatus.FAIL, CritiqueStatus.BLOCKED -> GateResult(GateStatus.BLOCKED, input, input.findings.map { GateIssue(it.code, it.message, true) })
    }
}

class ReadinessGate : BehaviorGate<ReadinessReport, ReadinessReport> {
    override fun evaluate(input: ReadinessReport): GateResult<ReadinessReport> =
        if (input.status == ReadinessStatus.READY && input.stages.all { it.passed }) GateResult(GateStatus.PASSED, input)
        else GateResult(GateStatus.BLOCKED, input, input.blockers.ifEmpty { listOf("readiness stage não aprovado") }.map { GateIssue("readiness.blocked", it) })
}

class LearningGate : BehaviorGate<LearningInput, ExecutionEvidence> {
    override fun evaluate(input: LearningInput): GateResult<ExecutionEvidence> =
        if (input.evidence.verified) GateResult(GateStatus.PASSED, input.evidence)
        else GateResult(GateStatus.BLOCKED, input.evidence, listOf(GateIssue("learning.unverified", "evidência não verificada")))
}

data class LearningInput(val evidence: ExecutionEvidence, val outcome: String)
