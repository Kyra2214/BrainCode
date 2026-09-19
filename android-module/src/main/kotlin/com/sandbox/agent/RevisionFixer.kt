package com.sandbox.agent

import com.brain.behavior.CritiqueResult
import com.brain.behavior.FixPlanStage
import com.brain.behavior.FixStage
import com.brain.behavior.FixVerifyLearn
import com.brain.behavior.LearnStage
import com.brain.behavior.ScanStage
import com.brain.behavior.VerificationResult
import com.brain.behavior.VerifyStage
import com.brain.planner.PlanoExecucao

/**
 * Adapta a correção comportamental ao plano que será reexecutado no Android.
 * Implementações devem alterar o plano de forma verificável e idempotente.
 */
fun interface RevisionFixer {
    fun fix(plan: PlanoExecucao, critique: CritiqueResult, attempt: Int): FixApplication
}

data class FixApplication(
    val plan: PlanoExecucao,
    val summary: String,
    val evidence: String
) {
    init {
        require(summary.isNotBlank()) { "correção precisa de resumo" }
        require(evidence.isNotBlank()) { "correção precisa produzir evidência" }
    }
}

/** Correção default segura: registra a revisão e marca a nova tentativa no contexto dos passos. */
class ContextRevisionFixer : RevisionFixer {
    override fun fix(plan: PlanoExecucao, critique: CritiqueResult, attempt: Int): FixApplication {
        val marker = "brain-revision-attempt:$attempt"
        val revised = plan.copy(
            assumptions = plan.assumptions + marker,
            passos = plan.passos.map { step ->
                if (step.parametros.contains(marker)) step
                else step.copy(parametros = step.parametros + marker)
            }
        )
        return FixApplication(
            plan = revised,
            summary = "plano corrigido para a tentativa $attempt: ${critique.findings.size} finding(s)",
            evidence = "${marker}:${critique.findings.joinToString(",") { it.code }}"
        )
    }
}

/** Executa a sequência universal de scan → plan → fix → verify → learn para a revisão. */
class RevisionFixVerifyLearn(
    private val fixer: RevisionFixer
) {
    fun apply(plan: PlanoExecucao, critique: CritiqueResult, attempt: Int, verify: (FixApplication) -> VerificationResult): FixApplicationResult {
        val result = FixVerifyLearn(
            object : ScanStage<CritiqueResult> { override fun scan() = critique },
            object : FixPlanStage<CritiqueResult, RevisionRequest> { override fun plan(scan: CritiqueResult) = RevisionRequest(plan, scan, attempt) },
            object : FixStage<RevisionRequest, FixApplication> { override fun fix(request: RevisionRequest) = fixer.fix(request.plan, request.critique, request.attempt) },
            object : VerifyStage<FixApplication> { override fun verify(fix: FixApplication) = verify(fix) },
            object : LearnStage<FixApplication, Unit> { override fun learn(fix: FixApplication, verification: VerificationResult) = Unit }
        ).run()
        return FixApplicationResult(result.status.name, result.fix, result.verification, result.issues)
    }
}

data class RevisionRequest(val plan: PlanoExecucao, val critique: CritiqueResult, val attempt: Int)
data class FixApplicationResult(
    val status: String,
    val application: FixApplication?,
    val verification: VerificationResult?,
    val issues: List<String>
) {
    val completed: Boolean get() = status == "COMPLETED" && application != null && verification?.passed == true
}
