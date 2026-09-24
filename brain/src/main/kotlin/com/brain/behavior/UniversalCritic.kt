package com.brain.behavior

/** Entrada neutra para criticar qualquer resultado, não apenas prompts. */
data class CritiqueInput(
    val objective: String,
    val requirements: List<String>,
    val constraints: List<String> = emptyList(),
    val evidence: List<ExecutionEvidence> = emptyList(),
    val result: String,
    val highRisk: Boolean = false
)

class UniversalCritic {
    fun evaluate(input: CritiqueInput): CritiqueResult {
        val findings = mutableListOf<CritiqueFinding>()
        if (input.objective.isBlank()) findings += CritiqueFinding("objective.empty", "objetivo vazio", FindingSeverity.BLOCKING)
        if (input.result.isBlank()) findings += CritiqueFinding("result.empty", "resultado vazio", FindingSeverity.BLOCKING)
        input.requirements.filterNot { RequirementMatcher.isPresent(it, input.result) }.forEach {
            findings += CritiqueFinding("requirement.missing", "requisito ausente: $it", FindingSeverity.HIGH, it)
        }
        input.constraints.filter { it.startsWith("must:") }.filterNot { RequirementMatcher.isPresent(it.removePrefix("must:"), input.result) }.forEach {
            findings += CritiqueFinding("constraint.violated", "restrição não demonstrada: $it", FindingSeverity.BLOCKING)
        }
        if (input.highRisk && input.evidence.none { it.verified }) {
            findings += CritiqueFinding("evidence.missing", "mudança de alto risco sem evidência verificada", FindingSeverity.BLOCKING)
        }
        val status = when {
            findings.any { it.severity == FindingSeverity.BLOCKING } -> CritiqueStatus.BLOCKED
            findings.isNotEmpty() -> CritiqueStatus.NEEDS_REVISION
            else -> CritiqueStatus.PASS
        }
        return CritiqueResult(status, findings, input.requirements)
    }

}

class DoubtDrivenReview {
    fun review(input: CritiqueInput, critique: CritiqueResult): RevisionDecision {
        if (critique.status == CritiqueStatus.BLOCKED) return RevisionDecision(RevisionAction.ABORT, "finding bloqueante requer correção ou evidência", critique.checkedCriteria, 0)
        if (critique.status == CritiqueStatus.NEEDS_REVISION) return RevisionDecision(RevisionAction.REVISE, "findings verificáveis exigem revisão", critique.findings.mapNotNull { it.criterionId }, 2)
        if (input.highRisk && input.evidence.none { it.verified }) return RevisionDecision(RevisionAction.ABORT, "dúvida não resolvida: falta evidência verificada", maxAttempts = 0)
        return RevisionDecision(RevisionAction.ACCEPT, "objetivo, requisitos, restrições e evidências suficientes", critique.checkedCriteria, 0)
    }
}
