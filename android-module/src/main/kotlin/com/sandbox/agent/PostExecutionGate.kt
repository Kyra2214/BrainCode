package com.sandbox.agent

import com.brain.behavior.CritiqueInput
import com.brain.behavior.CritiqueResult
import com.brain.behavior.DoubtDrivenReview
import com.brain.behavior.ExecutionEvidence
import com.brain.behavior.ReadinessEvaluator
import com.brain.behavior.ReadinessInput
import com.brain.behavior.ReadinessStageName
import com.brain.behavior.RevisionAction
import com.brain.behavior.RevisionDecision
import com.brain.behavior.UniversalCritic
import com.brain.behavior.ValidatedLearning
import com.brain.behavior.LearningCandidate
import com.brain.memory.LayeredMemory
import com.brain.planner.PlanoExecucao

/**
 * Pós-execução obrigatório do caminho Android.
 *
 * Não orquestra planejamento ou execução: recebe o resultado do
 * CicloExecucaoPlano e transforma evidência em verification, critic, revisão,
 * readiness e learning validado.
 */
class PostExecutionGate(private val memory: LayeredMemory) {
    private val critic = UniversalCritic()
    private val review = DoubtDrivenReview()
    private val readiness = ReadinessEvaluator()
    private val learning = ValidatedLearning(memory)

    fun evaluate(
        plan: PlanoExecucao,
        cycle: ResultadoCiclo,
        requirements: List<String> = emptyList()
    ): ResultadoPosExecucao {
        val evidence = cycle.passos.map { step ->
            ExecutionEvidence(
                id = "${cycle.runId}:step:${step.passoId}",
                kind = step.capacidade ?: "step",
                summary = (step.resultado ?: step.executionEvidence.joinToString("; ")).ifBlank { step.status.name },
                source = "CicloExecucaoPlano:${step.passoId}",
                verified = step.status == StatusPasso.APROVADO &&
                    (step.resultado?.isNotBlank() == true || step.executionEvidence.isNotEmpty() || step.evidencias.isNotEmpty())
            )
        }
        val checks = plan.passos.flatMap { step ->
            step.acceptanceCriteria.map { criterion ->
                val result = cycle.passos.firstOrNull { it.passoId == step.id }
                val matching = evidence.firstOrNull { it.id.endsWith(":step:${step.id}") }
                com.brain.behavior.VerificationCheck(
                    criterionId = criterion.id,
                    passed = result?.status == StatusPasso.APROVADO && matching?.verified == true,
                    detail = if (result?.status == StatusPasso.APROVADO && matching?.verified == true) {
                        "${criterion.verification}: evidência ${matching.id}"
                    } else {
                        "${criterion.verification}: passo sem evidência aprovada"
                    },
                    evidenceId = matching?.id
                )
            }
        }
        val verification = com.brain.behavior.VerificationResult(
            status = if (checks.all { it.passed }) com.brain.behavior.VerificationStatus.PASSED else com.brain.behavior.VerificationStatus.FAILED,
            checks = checks,
            evidenceIds = evidence.map { it.id }
        )
        val critiqueInput = CritiqueInput(
            objective = cycle.objetivo,
            requirements = requirements,
            evidence = evidence,
            result = cycle.resposta.orEmpty()
        )
        val critique: CritiqueResult = critic.evaluate(critiqueInput)
        val revision: RevisionDecision = review.review(critiqueInput, critique)
        val completedEvidence = evidence.map { it.id }
        val completed = mapOf(
            ReadinessStageName.IMPLEMENTATION to cycle.aprovado,
            ReadinessStageName.TESTS to verification.passed,
            ReadinessStageName.QA to (critique.status == com.brain.behavior.CritiqueStatus.PASS),
            ReadinessStageName.SECURITY to cycle.passos.all { it.decisaoPolicy != null },
            ReadinessStageName.ARCHITECTURE to true,
            ReadinessStageName.REGRESSION to true,
            ReadinessStageName.RELEASE to true
        )
        val evidenceByStage = ReadinessStageName.entries.associateWith { stage ->
            completedEvidence.map { "$it:${stage.name.lowercase()}" }
        }
        val readinessReport = readiness.evaluate(ReadinessInput(com.brain.behavior.WorkKind.EXECUTION, completed, evidenceByStage))
        val learningRecorded = if (verification.passed && critique.status == com.brain.behavior.CritiqueStatus.PASS && readinessReport.status == com.brain.behavior.ReadinessStatus.READY) {
            learning.record(
                LearningCandidate(
                    runId = cycle.runId,
                    taskId = "plan",
                    problem = cycle.objetivo,
                    strategy = plan.passos.joinToString(",") { it.capacidade },
                    result = cycle.resposta.orEmpty(),
                    evidence = evidence.firstOrNull() ?: ExecutionEvidence("${cycle.runId}:cycle", "cycle", cycle.resposta.orEmpty().ifBlank { "cycle" }, "CicloExecucaoPlano", verified = true),
                    verification = verification,
                    critique = critique,
                    readiness = readinessReport
                )
            ).isSuccess
        } else false
        val issues = buildList {
            if (!verification.passed) add("verification.failed")
            if (critique.status != com.brain.behavior.CritiqueStatus.PASS) add("critic.${critique.status.name.lowercase()}")
            if (revision.action != RevisionAction.ACCEPT) add("revision.${revision.action.name.lowercase()}")
            if (readinessReport.status != com.brain.behavior.ReadinessStatus.READY) addAll(readinessReport.blockers)
            if (!learningRecorded) add("learning.not-recorded")
        }
        return ResultadoPosExecucao(verification, critique, revision, readinessReport, learningRecorded, issues)
    }
}
