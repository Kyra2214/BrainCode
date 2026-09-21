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
import com.brain.behavior.CritiqueFinding
import com.brain.behavior.FindingSeverity
import com.brain.behavior.ValidatedLearning
import com.brain.behavior.LearningCandidate
import com.brain.memory.LayeredMemory
import com.brain.planner.PlanoExecucao
import com.brain.validation.ValidationEngine
import com.brain.validation.ValidationSubject
import com.brain.validation.ValidationResult

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
    private val validation = ValidationEngine()

    fun evaluate(
        plan: PlanoExecucao,
        cycle: ResultadoCiclo,
        requirements: List<String> = emptyList(),
        attempt: Int = 1,
        previousValidationResultId: String? = null,
        createPhase: String? = null
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
        val baseCritique: CritiqueResult = critic.evaluate(critiqueInput)
        val researchWasUsed = cycle.researchSources.any { source ->
            val output = cycle.resposta.orEmpty().lowercase()
            listOf(source.title, source.source, source.relevantContent)
                .flatMap { it.lowercase().split(Regex("[^\\p{L}\\p{Nd}]+")) }
                .filter { it.length >= 5 }
                .any { it in output }
        }
        val critique: CritiqueResult = if (cycle.researchSources.isNotEmpty() && !researchWasUsed) {
            baseCritique.copy(
                status = if (baseCritique.status == com.brain.behavior.CritiqueStatus.BLOCKED) baseCritique.status else com.brain.behavior.CritiqueStatus.NEEDS_REVISION,
                findings = baseCritique.findings + CritiqueFinding("research.unused", "fontes de pesquisa disponíveis não refletidas no resultado", FindingSeverity.MEDIUM)
            )
        } else baseCritique
        val reviewedRevision = review.review(critiqueInput, critique)
        val revision: RevisionDecision = if (!verification.passed && reviewedRevision.action == RevisionAction.ACCEPT) {
            RevisionDecision(
                action = RevisionAction.REVISE,
                reason = "verification falhou e exige nova execução",
                targetCriteria = verification.checks.filterNot { it.passed }.map { it.criterionId },
                maxAttempts = 2
            )
        } else reviewedRevision
        val completed = mapOf(
            ReadinessStageName.IMPLEMENTATION to (cycle.passos.isNotEmpty() && cycle.passos.all { it.status == StatusPasso.APROVADO }),
            ReadinessStageName.TESTS to verification.passed,
            ReadinessStageName.QA to (critique.status == com.brain.behavior.CritiqueStatus.PASS),
            ReadinessStageName.SECURITY to (cycle.passos.isNotEmpty() && cycle.passos.all { it.decisaoPolicy?.decision?.name == "ALLOW" }),
            ReadinessStageName.ARCHITECTURE to (plan.passos.isNotEmpty() && plan.passos.all { it.id.isNotBlank() && it.capacidade.isNotBlank() }),
            ReadinessStageName.REGRESSION to (verification.passed && cycle.passos.isNotEmpty() && cycle.passos.all { it.status == StatusPasso.APROVADO }),
            ReadinessStageName.RELEASE to (verification.passed && critique.status == com.brain.behavior.CritiqueStatus.PASS)
        )
        fun ownEvidence(stage: ReadinessStageName, ids: List<String>, enabled: Boolean): List<String> =
            if (enabled) ids.distinct().mapIndexed { index, id ->
                // O prefixo do estágio separa as categorias; o índice torna o ID
                // estável e único mesmo se um plano repetir uma etapa/critério.
                "${cycle.runId}:readiness:${stage.name.lowercase()}:$index:$id"
            } else emptyList()
        val evidenceByStage = mapOf(
            ReadinessStageName.IMPLEMENTATION to ownEvidence(ReadinessStageName.IMPLEMENTATION, cycle.passos.map { "step:${it.passoId}" }, completed.getValue(ReadinessStageName.IMPLEMENTATION)),
            ReadinessStageName.TESTS to ownEvidence(ReadinessStageName.TESTS, verification.checks.filter { it.passed }.map { "criterion:${it.criterionId}" }, completed.getValue(ReadinessStageName.TESTS)),
            ReadinessStageName.QA to ownEvidence(ReadinessStageName.QA, listOf("critic:pass"), completed.getValue(ReadinessStageName.QA)),
            ReadinessStageName.SECURITY to ownEvidence(ReadinessStageName.SECURITY, cycle.passos.map { "policy:${it.passoId}" }, completed.getValue(ReadinessStageName.SECURITY)),
            ReadinessStageName.ARCHITECTURE to ownEvidence(ReadinessStageName.ARCHITECTURE, listOf("plan:${plan.passos.joinToString(",") { it.id }}"), completed.getValue(ReadinessStageName.ARCHITECTURE)),
            ReadinessStageName.REGRESSION to ownEvidence(ReadinessStageName.REGRESSION, listOf("verification:${verification.status.name.lowercase()}"), completed.getValue(ReadinessStageName.REGRESSION)),
            ReadinessStageName.RELEASE to ownEvidence(ReadinessStageName.RELEASE, listOf("gate:${verification.status.name.lowercase()}:${critique.status.name.lowercase()}"), completed.getValue(ReadinessStageName.RELEASE))
        )
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
                    critique = finalCritique,
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
        val selfE2E = cycle.passos.map { step ->
            val stepEvidence = step.executionEvidence + step.evidencias.map { it.toString() }
            validation.selfAgent(
                ValidationSubject(
                    capability = step.capacidade ?: "step",
                    taskId = step.passoId,
                    agentId = step.capacidade,
                    result = step.resultado.orEmpty(),
                    evidence = stepEvidence,
                    requirements = requirements
                ),
                stage = "agent:" + (step.capacidade ?: "step"),
                attempt = attempt,
                previousResultId = previousValidationResultId
            )
        }
        val chatStep = cycle.passos.firstOrNull { it.capacidade == "chat.respond" }
        val promptStep = cycle.passos.firstOrNull { it.capacidade == "prompt.library.generate" }
        val createStep = cycle.passos.firstOrNull { it.capacidade.startsWith("workspace.") || it.capacidade.startsWith("sandbox.") }
        val doorE2E: ValidationResult? = when {
            createPhase != null && createStep != null -> {
                val evidenceIds = createStep.executionEvidence + createStep.evidencias.map { it.toString() } +
                    listOfNotNull(createStep.approvalId?.let { "approval:" + it })
                validation.productPhase(
                    createPhase,
                    ValidationSubject(
                        capability = createStep.capacidade ?: "create",
                        taskId = createStep.passoId,
                        door = com.brain.secretary.Door.CREATE,
                        result = createStep.resultado.orEmpty(),
                        requirements = requirements,
                        evidence = evidenceIds
                    ),
                    stage = "door.create:" + createPhase,
                    attempt = attempt,
                    previousResultId = previousValidationResultId
                )
            }
            chatStep != null -> {
                val evidenceIds = chatStep.executionEvidence + chatStep.evidencias.map { it.toString() }
                validation.lightChat(
                    ValidationSubject(
                        capability = "chat.respond",
                        taskId = chatStep.passoId,
                        door = com.brain.secretary.Door.CHAT,
                        result = chatStep.resultado.orEmpty(),
                        evidence = evidenceIds,
                        requiresInput = evidenceIds.any { it == "chat:clarification-question" },
                        restrictions = emptySet()
                    ),
                    stage = "door.chat",
                    attempt = attempt,
                    previousResultId = previousValidationResultId
                )
            )
            promptStep != null -> {
                val evidenceIds = promptStep.executionEvidence + promptStep.evidencias.map { it.toString() }
                validation.promptContent(
                    ValidationSubject(
                        capability = "prompt.library.generate",
                        taskId = promptStep.passoId,
                        door = com.brain.secretary.Door.PROMPT,
                        result = promptStep.resultado.orEmpty(),
                        requirements = requirements,
                        evidence = evidenceIds
                    ),
                    stage = "door.prompt",
                    attempt = attempt,
                    previousResultId = previousValidationResultId
                )
            }
            else -> null
        }
        val validationFindings = selfE2E.filterNot { it.passed }.map {
            CritiqueFinding(
                code = "validation." + it.contractId,
                message = "Self-E2E falhou: " + it.failedChecks.joinToString(","),
                severity = FindingSeverity.BLOCKING
            )
        } + listOfNotNull(doorE2E?.takeUnless { it.passed || it.status == com.brain.validation.ValidationStatus.NEEDS_INPUT }?.let {
            CritiqueFinding(
                code = "door-e2e." + it.contractId,
                message = "E2E da porta falhou: " + it.failedChecks.joinToString(","),
                severity = FindingSeverity.BLOCKING
            )
        })
        val finalCritique = if (validationFindings.isEmpty()) critique else critique.copy(
            status = CritiqueStatus.NEEDS_REVISION,
            findings = critique.findings + validationFindings
        )
        val effectiveRevision = if (validationFindings.isEmpty()) revision else RevisionDecision(
            action = if (doorE2E?.status == com.brain.validation.ValidationStatus.NEEDS_INPUT) RevisionAction.ASK_CLARIFICATION else RevisionAction.REVISE,
            reason = "validação Self-E2E/E2E encontrou finding bloqueante",
            targetCriteria = validationFindings.map { it.code },
            maxAttempts = 3
        )
        return ResultadoPosExecucao(
            verification = verification,
            critique = finalCritique,
            revision = effectiveRevision,
            readiness = readinessReport,
            learningRecorded = learningRecorded,
            issues = issues + selfE2E.filterNot { it.passed }.map { "self-e2e:" + it.contractId } +
                listOfNotNull(doorE2E?.takeUnless { it.passed }?.let { "door-e2e:" + it.status.name.lowercase() }),
            selfE2E = selfE2E,
            doorE2E = doorE2E
        )
    }
}
