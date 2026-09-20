package com.sandbox.agent

import com.brain.planner.PassoPlano
import com.brain.planner.PlanoExecucao
import com.brain.capability.CapabilityAvailability
import com.brain.capability.CapabilityCategory
import com.brain.capability.CapabilityDefinition
import com.brain.capability.CapabilityDiscovery
import com.brain.capability.CapabilityProvenance
import com.brain.capability.CapabilityProvider
import com.brain.capability.CapabilityRegistry
import com.brain.dispatch.Dispatcher
import com.brain.gateway.ActionGateway
import com.brain.gateway.ActionExecutor
import com.brain.gateway.InMemoryActionAuditLog
import com.brain.job.DurableJobRunner
import com.brain.job.JobStore
import com.brain.execution.RiskClass
import com.brain.policy.PolicyBroker
import com.brain.policy.FileApprovalStore
import com.brain.policy.ApprovalRequest
import com.brain.router.ApiCatalogRegistry
import com.brain.router.DefaultAIRouter
import com.brain.router.InMemoryApiCatalog
import com.brain.account.AccountRouter
import com.sandbox.runtime.ManagedSandboxRuntime
import com.brain.workflow.WorkflowEngine
import com.brain.workflow.WorkflowManifest
import com.brain.workflow.WorkflowNode
import com.brain.workflow.WorkflowStepResult
import com.brain.prompt.PromptLibrary
import com.brain.prompt.PromptOutcomeTracker
import com.brain.prompt.PromptOutcomeTrackers
import com.brain.events.BrainEvent
import com.brain.events.EventStore
import com.brain.events.InMemoryEventStore
import com.brain.retrieval.PromptLibraryRetrievalSource
import com.brain.retrieval.Retrieval
import com.brain.retrieval.RetrievalQuery
import com.brain.reasoning.ReasoningEngine
import com.brain.behavior.PlanningGate
import com.brain.behavior.RequirementGate
import com.brain.behavior.GateStatus
import com.brain.behavior.ClarificationQuestion
import com.brain.behavior.BehaviorDiagnostics
import com.brain.behavior.EventStoreBehaviorTraceSink
import com.brain.behavior.VerificationResult
import com.brain.reasoning.TaskState
import com.brain.execution.OperationalState
import com.brain.execution.Observation
import com.brain.memory.LayeredMemory
import com.brain.memory.Provenance
import com.brain.planner.TreeOfThoughts
import com.brain.secretary.OrderIntent
import com.brain.secretary.Door
import com.brain.secretary.CreatePhase
import com.brain.core.CreationWorkflowPlanner
import com.brain.planning.FilePlanningArtifactStore
import com.brain.planning.PlanningArtifact
import com.brain.planning.PlanningAgent
import java.io.File
import java.time.Instant
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

/**
 * Primeira fatia vertical da unificação Brain + Sandbox.
 *
 * O catálogo de APIs é instalado pelo app através do ApiCatalogRegistry. Se o
 * app ainda não tiver carregado as chaves/catálogo, mantém o catálogo vazio
 * como fallback seguro — nunca inventa provider/modelo.
 */
class BrainSandboxController(
    runtime: ManagedSandboxRuntime,
    rootfsDir: File,
    private val actor: String = "android-app",
    promptLibrary: PromptLibrary? = null,
    private val promptOutcomeTracker: PromptOutcomeTracker? = promptLibrary?.let { PromptOutcomeTrackers.forLibrary(it) },
    capabilityProviders: List<CapabilityProvider> = emptyList(),
    capabilityExecutors: Map<String, ActionExecutor> = emptyMap(),
    private val apiKeyAvailable: () -> Boolean = { true },
    private val authorizedAccountIds: Set<String> = emptySet(),
    private val events: EventStore = InMemoryEventStore(),
    private val revisionFixer: RevisionFixer = FindingsRevisionFixer(),
    private val secretary: com.brain.secretary.DeterministicSecretary = com.brain.secretary.DeterministicSecretary()
) {
    private val behaviorDiagnostics = BehaviorDiagnostics(EventStoreBehaviorTraceSink(events, "android-local"))
    private val dynamicCapabilityProviders = capabilityProviders
    private val approvals = FileApprovalStore(File(rootfsDir.parentFile ?: rootfsDir, "approvals.jsonl"))
    private val planningArtifacts = FilePlanningArtifactStore(File(rootfsDir.parentFile ?: rootfsDir, "planning-artifacts.jsonl"))
    private val sandbox = Sandbox(runtime = runtime, rootfsDir = rootfsDir)
    private val capabilities = CapabilityRegistry(
        listOf(
            capability("sandbox.health", setOf("sandbox.health")),
            capability("sandbox.info", setOf("network.research")),
            capability("workspace.generate", setOf("workspace.write")),
            capability("prompt.library.generate", setOf("prompt.library.write")),
            capability("sandbox.build", emptySet()),
            capability("sandbox.test", setOf("sandbox.code")),
            capability("sandbox.diagnose", setOf("brain.analyze")),
            capability("chat.respond", emptySet()),
            capability("sandbox.clean", emptySet())
        ) + capabilityProviders.flatMap { it.capabilities().toList() }
    )
    private val policy = PolicyBroker(
        allowedCapabilities = capabilities.all().flatMap { listOf(it.id) + it.providedCapabilities },
        actorCapabilities = mapOf(actor to capabilities.all().flatMap { listOf(it.id) + it.providedCapabilities })
    ).withCapabilityRegistry(capabilities)
    private val actionGateway = ActionGateway(
        registry = capabilities,
        policy = policy,
        executor = CompositeActionExecutor(capabilityExecutors, BrainActionExecutor(sandbox)),
        audit = InMemoryActionAuditLog()
    )
    private val dispatcher = Dispatcher(CapabilityDiscovery(capabilities), actionGateway)

    /** Recalcula metadados dinâmicos, como disponibilidade de plugins instalados. */
    fun refreshCapabilities() {
        dynamicCapabilityProviders
            .flatMap { it.capabilities().toList() }
            .forEach { definition ->
                if (capabilities.getById(definition.id) != null) {
                    capabilities.update(definition)
                }
            }
    }
    private val durableJobs = DurableJobRunner(
        JobStore(File(rootfsDir, "brain-jobs.json")),
        WorkflowEngine(File(rootfsDir, "brain-workflows.json"))
    )
    private val promptRetrieval = promptLibrary?.let { Retrieval(listOf(PromptLibraryRetrievalSource.from(it))) }
    private val apiCatalog = ApiCatalogRegistry.current() ?: InMemoryApiCatalog(emptyList())
    private val bridge = BrainSandboxExecutionBridge(
        CicloExecucaoPlano(
            policyBroker = policy,
            sandbox = sandbox,
            router = DefaultAIRouter(),
            catalog = apiCatalog,
            approvalStore = approvals,
                dispatcher = dispatcher,
                accountRouter = AccountRouter(),
                authorizedAccountIds = authorizedAccountIds
        )
    )
    private val reasoningEngine = ReasoningEngine()
    private val requirementGate = RequirementGate()
    private val planningGate = PlanningGate()
    private val treeOfThoughts = TreeOfThoughts()
    private val layeredMemory = LayeredMemory()
    private val postExecutionGate = PostExecutionGate(layeredMemory)
    private val pendingCreateScopes = mutableMapOf<String, com.brain.secretary.DoorScope>()
    private val pendingCreatePlans = mutableMapOf<String, PlanoExecucao>()
    @Volatile private var taskState: TaskState? = null

    /** Executa o primeiro caso de uso real do Brain dentro do Sandbox preparado. */
    fun healthCheck(runId: String): ResultadoCiclo {
        require(runId.isNotBlank()) { "runId não pode ser vazio" }
        val plano = PlanoExecucao(
            objetivo = "verificar saúde do Sandbox pelo Brain",
            passos = listOf(
                PassoPlano(
                    id = "sandbox-health",
                    capacidade = "sandbox.health",
                    criterioSucesso = "a capability sandbox.health deve concluir sem falha"
                )
            )
        )
        return executeWithEvents(plano, runId) { attemptPlan, attemptRunId ->
            bridge.authorizeAndExecute(attemptPlan, runId = attemptRunId, actor = actor)
        }
    }

    fun executePlan(plano: PlanoExecucao, runId: String = "plan-${System.currentTimeMillis()}"): ResultadoCiclo {
        val gate = planningGate.evaluate(plano)
        if (!gate.isSuccessful) return blockedCycle(plano, runId, gate.issues.joinToString("; ") { it.message })
        return executeWithEvents(plano, runId) { attemptPlan, attemptRunId -> bridge.authorizeAndExecute(attemptPlan, runId = attemptRunId, actor = actor) }
    }

    fun resumePlan(plano: PlanoExecucao, runId: String, approvalId: String): ResultadoCiclo =
        executeWithEvents(pendingCreatePlans[runId] ?: plano, runId) {
            attemptPlan, _ -> bridge.resume(attemptPlan, runId = runId, actor = actor, approvalId = approvalId, doorScope = pendingCreateScopes[runId])
        }.also { pendingCreateScopes.remove(runId); pendingCreatePlans.remove(runId) }

    fun approve(approvalId: String): Boolean = approvals.decide(approvalId, approved = true)?.status == com.brain.policy.ApprovalStatus.APPROVED

    fun planningArtifact(runId: String): PlanningArtifact? = planningArtifacts.get(runId)

    fun approvalDemoPlan(): PlanoExecucao = PlanoExecucao(
        objetivo = "executar plano de demonstração com aprovação humana",
        passos = listOf(
            PassoPlano(
                id = "approval-demo",
                capacidade = "sandbox.health",
                criterioSucesso = "sandbox.health deve concluir após aprovação",
                riskClass = RiskClass.HIGH
            )
        )
    )

    /** Entrada real do chat: objetivo → planner no caller → ciclo autorizado → dispatcher/gateway/sandbox. */
    fun executeObjective(
        objective: String,
        runId: String = "chat-${System.currentTimeMillis()}",
        onPasso: (ResultadoPasso) -> Unit = {},
        intent: OrderIntent? = null
    ): ResultadoCiclo {
        emit(runId, "chat", "TaskCreated", mapOf("objective" to objective.take(500)))
        intent?.let { designated ->
            emit(
                runId,
                "secretary",
                "DoorDesignated",
                mapOf(
                    "door" to designated.door.name,
                    "phase" to designated.phase.name,
                    "restrictions" to designated.restrictions.joinToString(",") { it.name }
                )
            )
        }
        val reasoning = reasoningEngine.analyze(objective)
        val references = objective.lineSequence().filter { it.startsWith("Referências resolvidas:", ignoreCase = true) }.toList()
        val planningArtifact = PlanningAgent().plan(runId, reasoning, references)
        planningArtifacts.save(planningArtifact)
        emit(runId, "planning", "PlanningArtifactCreated", mapOf("artifactId" to planningArtifact.artifactId, "status" to planningArtifact.status.name, "requirements" to planningArtifact.requirements.size.toString(), "pending" to planningArtifact.pending.size.toString(), "references" to planningArtifact.references.size.toString()))
        val requirementGateResult = requirementGate.evaluate(reasoning)
        if (!requirementGateResult.isSuccessful) {
            if (requirementGateResult.status == GateStatus.NEEDS_CLARIFICATION && intent?.door == com.brain.secretary.Door.CHAT) {
                val clarification = ClarificationQuestion.from(objective, requirementGateResult.issues)
                emit(runId, "requirements", "ClarificationRequested", mapOf("missing" to clarification.missingRequirements.joinToString("|"), "question" to clarification.question))
                val clarificationPlan = PlanoExecucao(
                    objective,
                    listOf(PassoPlano("clarificar", "chat.respond", "pergunta de esclarecimento não vazia", parametros = listOf(clarification.question, "clarification.status=NEEDS_CLARIFICATION", "clarification.missing=${clarification.missingRequirements.joinToString("|")}")))
                )
                emit(runId, "plan", "PlanCreated", mapOf("steps" to "1", "kind" to "clarification"))
                return bridge.authorizeAndExecute(
                    clarificationPlan,
                    runId,
                    actor,
                    onPasso = { passo ->
                        emit(
                            runId,
                            passo.passoId,
                            "StepCompleted",
                            mapOf(
                                "passoId" to passo.passoId,
                                "status" to passo.status.name,
                                "motivo" to (passo.motivo ?: "")
                            )
                        )
                        onPasso(passo)
                    },
                    doorScope = intent.scope
                )
            }
            return blockedCycle(
                PlanoExecucao(objective, listOf(PassoPlano("requirements", "brain.requirements", "requisitos resolvidos"))),
                runId,
                requirementGateResult.issues.joinToString("; ") { it.message }
            )
        }
        taskState = TaskState(objective).withReasoning(reasoning)
        layeredMemory.rememberEpisode(objective, Provenance("brain:task-created", confidence = 1.0))
        val planningIntent = if (intent?.door == Door.CREATE && intent.phase < CreatePhase.APPROVED) {
            intent.copy(phase = CreatePhase.APPROVED, scope = intent.scope.copy(phase = CreatePhase.APPROVED))
        } else intent
        val planner = com.brain.planner.KeywordPlanner(if (planningIntent == null) com.brain.planner.KeywordFunctionSplitter() else com.brain.planner.DoorAwareSplitter(secretary))
        val basePlan = runBlockingPlanner {
            if (planningIntent == null) planner.planejar(reasoning.objective, reasoning)
            else planner.planejar(reasoning.objective, planningIntent, reasoning)
        }
        if (basePlan.passos.any { it.capacidade == "brain.analyze" } && !apiKeyAvailable()) {
            emit(runId, "reasoning", "LocalFallbackSelected", mapOf("reason" to "api-unavailable"))
        }
        val promptHit = promptRetrieval?.retrieve(RetrievalQuery(objective))?.hit
        val treeAssumptions = if (treeOfThoughts.shouldExplore(objective)) {
            val selection = treeOfThoughts.explore(objective)
            emit(runId, "planner", "ThoughtsExplored", mapOf("branches" to selection.explored.size.toString(), "selected" to selection.selected.name))
            setOf("tree-of-thoughts:${selection.selected.name}")
        } else emptySet()
        val plan = basePlan.copy(
            assumptions = basePlan.assumptions + treeAssumptions + if (promptHit != null) setOf("prompt-template:${promptHit.id}") else emptySet()
        )
        if (planningIntent?.door == Door.CREATE && planningIntent.phase >= CreatePhase.APPROVED) {
            val creation = CreationWorkflowPlanner.build(planningIntent, reasoning.requirements.map { it.text })
            emit(runId, "roadmap", "RoadmapCreated", mapOf("phases" to creation.roadmap.fases.size.toString(), "tasks" to creation.tasks.size.toString()))
            creation.assignments.forEach { assignment ->
                emit(runId, assignment.taskId, "TaskAssigned", mapOf("specialist" to assignment.specialistId, "capability" to assignment.capability))
                emit(runId, assignment.taskId, "SpecialistSelected", mapOf("specialist" to assignment.specialistId))
            }
        }
        if (intent?.door == Door.CREATE && intent.phase < CreatePhase.APPROVED) {
            val gatedStep = plan.passos.firstOrNull { it.capacidade.startsWith("workspace.") || it.capacidade.startsWith("sandbox.") }
            if (gatedStep != null) {
                val approval = approvals.create(
                    ApprovalRequest(
                        runId = runId,
                        taskId = gatedStep.id,
                        capability = gatedStep.capacidade,
                        resource = "create:${gatedStep.id}",
                        expiresAt = Instant.now().plusSeconds(3600)
                    )
                )
                pendingCreateScopes[runId] = planningIntent!!.scope
                pendingCreatePlans[runId] = plan
                emit(runId, "secretary", "CreateApprovalRequested", mapOf("approvalId" to approval.request.id, "phase" to intent.phase.name, "nextPhase" to CreatePhase.APPROVED.name))
                return ResultadoCiclo(
                    plan.objetivo,
                    runId,
                    listOf(ResultadoPasso(gatedStep.id, StatusPasso.AGUARDANDO_APROVACAO, motivo = "aprovação explícita necessária antes de ${gatedStep.capacidade}", approvalId = approval.request.id, capacidade = gatedStep.capacidade))
                )
            }
        }
        taskState = taskState?.withPlan(
            summary = plan.passos.joinToString(",") { "${it.id}:${it.capacidade}" },
            assumptions = plan.assumptions.toList()
        )
        var cycle: ResultadoCiclo? = null
        val job = durableJobs.run(
            jobId = "job-$runId",
            runId = runId,
            taskId = "plan",
            manifest = WorkflowManifest("brain-plan", "1.0.0", listOf(WorkflowNode("plan", "brain.plan", retryLimit = 0))),
            authorize = { it == "brain.plan" },
            execute = { node, attempt ->
                cycle = executeWithEvents(plan, runId, if (intent == null) emptyList() else reasoning.requirements.map { it.text }) { attemptPlan, attemptRunId ->
                    bridge.authorizeAndExecute(attemptPlan, attemptRunId, actor, onPasso = { passo ->
                        emit(
                            attemptRunId,
                            passo.passoId,
                            "StepCompleted",
                            mapOf(
                                "passoId" to passo.passoId,
                                "status" to passo.status.name,
                                "motivo" to (passo.motivo ?: "")
                            )
                        )
                        onPasso(passo)
                    }, doorScope = planningIntent?.scope)
                }
                WorkflowStepResult(
                    nodeId = node.id,
                    success = cycle?.aprovado == true,
                    attempts = attempt,
                    evidence = cycle?.passos.orEmpty().flatMap { it.evidencias }.map { it.toString() }
                )
            }
        )
        val finalCycle = requireNotNull(cycle) {
            "Workflow não produziu resultado do plano${job.error?.let { ": $it" } ?: ""}"
        }
        val operational = OperationalState(
            objective = objective,
            completed = finalCycle.passos.filter { it.status == StatusPasso.APROVADO }.map { it.passoId },
            observations = finalCycle.passos.map { Observation(it.passoId, it.status == StatusPasso.APROVADO, it.motivo ?: it.status.name) }
        )
        taskState = taskState?.withOperational(operational)
            ?.withCycle("${finalCycle.passos.count { it.status == StatusPasso.APROVADO }}/${finalCycle.passos.size} passos aprovados")
        finalCycle.researchSources.forEach { source ->
            layeredMemory.rememberEvidence(
                source,
                Provenance("web-research:${source.source}", confidence = source.confidence),
                source.validationStatus.name == "VERIFIED"
            )
        }
        return finalCycle
    }

    private fun blockedCycle(plan: PlanoExecucao, runId: String, reason: String): ResultadoCiclo =
        ResultadoCiclo(
            plan.objetivo,
            runId,
            plan.passos.map { step ->
                ResultadoPasso(step.id, StatusPasso.REPROVADO, motivo = reason, capacidade = step.capacidade)
            }
        )

    fun localEvents(runId: String? = null) = events.replay(runId)
    fun localEventsHealthy(): Boolean = events.verifyIntegrity()
    fun currentTaskState(): TaskState? = taskState
    fun memorySnapshot() = layeredMemory.evidences()

    /**
     * Fecha o ciclo de aprendizado do Prompt Creator com o sinal real de quem recebeu o prompt —
     * distinto do sucesso técnico (que só mede se a geração não quebrou). `actionId` é o mesmo
     * identificador exposto em `ResultadoPasso.actionId` do passo "produzir" daquele ciclo.
     * @return false se não havia um prompt entregue aguardando feedback para esse actionId
     * (já avaliado, actionId inválido, ou o passo técnico nunca teve sucesso).
     */
    fun registrarFeedbackDePrompt(actionId: String, positivo: Boolean): Boolean =
        promptOutcomeTracker?.recordUserFeedback(actionId, positivo) ?: false

    private fun executeWithEvents(
        plan: PlanoExecucao,
        runId: String,
        requirements: List<String> = emptyList(),
        action: (PlanoExecucao, String) -> ResultadoCiclo
    ): ResultadoCiclo {
        emit(runId, "plan", "PlanCreated", mapOf("steps" to plan.passos.size.toString()))
        var currentPlan = plan
        var attempt = 1
        val revisionAttempts = mutableListOf<RevisionAttemptTrace>()
        val candidates = mutableListOf<Pair<ResultadoCiclo, ResultadoPosExecucao>>()
        var previousOutputSignature: String? = null
        var previousFindingsSignature: String? = null
        while (attempt <= MAX_EXECUTION_ATTEMPTS) {
            val attemptRunId = "$runId:attempt-$attempt"
            emit(runId, "execution", "AttemptStarted", mapOf("attempt" to attempt.toString(), "attemptRunId" to attemptRunId))
            val startedAt = System.nanoTime()
            var technicalRetry = 0
            var result: ResultadoCiclo
            while (true) {
                val execution = runCatching { action(currentPlan, attemptRunId) }
                if (execution.isSuccess) {
                    result = requireNotNull(execution.getOrNull())
                    break
                }
                val error = execution.exceptionOrNull()
                emit(runId, "execution", "ExecutionFailed", mapOf("attemptRunId" to attemptRunId, "error" to (error?.message ?: "unknown").take(500), "technicalRetry" to technicalRetry.toString()))
                if (technicalRetry >= MAX_TECHNICAL_RETRIES) throw error ?: IllegalStateException("falha técnica desconhecida")
                technicalRetry++
                emit(runId, "execution", "TechnicalRetry", mapOf("attemptRunId" to attemptRunId, "retry" to technicalRetry.toString(), "max" to MAX_TECHNICAL_RETRIES.toString()))
                Thread.sleep(TECHNICAL_RETRY_BACKOFF_MS * technicalRetry)
            }
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
            promptOutcomeTracker?.let { tracker ->
                result.passos.forEach { passo ->
                    passo.actionId?.let { actionId ->
                        tracker.recordTechnicalOutcome(
                            actionId = actionId,
                            success = passo.status == StatusPasso.APROVADO,
                            cost = passo.custo,
                            elapsedMs = elapsedMs
                        )
                    }
                }
            }
            // Nota: `acceptanceCriteria.description` (ex.: "artefato produzido",
            // "evidência de pesquisa disponível") são rótulos de processo, verificados
            // via evidência em VerificationCheck (ver PostExecutionGate.checks acima).
            // Eles NÃO são requisitos de conteúdo e nunca devem ser buscados
            // literalmente no texto da resposta final — o UniversalCritic.requirements
            // é para isso, não para reaproveitar rótulos internos do plano. Reutilizá-los
            // aqui gerava falsos positivos "requirement.missing" (o texto do prompt
            // gerado nunca contém as palavras "artefato" ou "produzido"), travando QA e
            // RELEASE em readiness=BLOCKED mesmo com verification=PASSED e critique
            // sem findings de conteúdo reais.
            val postExecution = postExecutionGate.evaluate(
                plan = currentPlan,
                cycle = result,
                requirements = requirements
            )
            val finalResult = result.copy(
                runId = runId,
                posExecucao = postExecution.copy(revisionAttempts = revisionAttempts.toList())
            )
            candidates += result to postExecution
            val outputSignature = result.resposta.orEmpty().trim()
            val findingsSignature = postExecution.critique.findings.joinToString("|") { "${it.code}:${it.message}" }
            if (attempt > 1 && outputSignature == previousOutputSignature && findingsSignature == previousFindingsSignature &&
                postExecution.critique.status != com.brain.behavior.CritiqueStatus.PASS) {
                val noProgress = finalResult.copy(posExecucao = postExecution.copy(
                    issues = postExecution.issues + "revision.no-progress",
                    revisionAttempts = revisionAttempts + RevisionAttemptTrace(attemptRunId, attempt, "ABORT", "saída e findings idênticos à tentativa anterior")
                ))
                emit(runId, "revision", "RevisionAborted", mapOf("reason" to "no-progress", "attempts" to attempt.toString()))
                return noProgress
            }
            previousOutputSignature = outputSignature
            previousFindingsSignature = findingsSignature
            emit(
                attemptRunId,
                "post-execution",
                when {
                    !finalResult.aprovado -> "PostExecutionBlocked"
                    postExecution.learningRecorded -> "ValidatedLearningRecorded"
                    else -> "ExecutionValidated"
                },
                mapOf(
                    "verification" to postExecution.verification.status.name,
                    "critic" to postExecution.critique.status.name,
                    "revision" to postExecution.revision.action.name,
                    "readiness" to postExecution.readiness.status.name,
                    "learning" to postExecution.learningRecorded.toString(),
                    "attempt" to attempt.toString(),
                    "findings" to postExecution.critique.findings.joinToString(" | ") { "${it.code}: ${it.message}" }.take(1000)
                )
            )
            if (postExecution.revision.action != com.brain.behavior.RevisionAction.REVISE) {
                emit(runId, "execution", if (finalResult.aprovado) "Delivered" else "ValidationFailed", mapOf("approved" to finalResult.aprovado.toString(), "attempts" to attempt.toString()))
                return finalResult
            }
            if (attempt == MAX_EXECUTION_ATTEMPTS) {
                val best = candidates.minByOrNull { (_, gate) -> gate.critique.findings.sumBy { finding -> when (finding.severity) {
                    com.brain.behavior.FindingSeverity.BLOCKING -> 1000
                    com.brain.behavior.FindingSeverity.HIGH -> 100
                    com.brain.behavior.FindingSeverity.MEDIUM -> 10
                    com.brain.behavior.FindingSeverity.LOW -> 1
                } } } ?: (result to postExecution)
                val exhausted = best.first.copy(runId = runId, posExecucao = best.second.copy(
                    issues = best.second.issues + "revision.max-attempts-exceeded",
                    revisionAttempts = revisionAttempts + RevisionAttemptTrace(attemptRunId, attempt, "ABORT", "máximo de tentativas atingido")
                ))
                emit(runId, "revision", "RevisionAborted", mapOf("reason" to "max-attempts", "attempts" to attempt.toString()))
                return exhausted
            }
            val nextAttempt = attempt + 1
            val previousSignature = planSignature(currentPlan)
            val fix = RevisionFixVerifyLearn(revisionFixer).apply(currentPlan, postExecution.critique, nextAttempt) { application ->
                val changed = planSignature(application.plan) != previousSignature
                VerificationResult(
                    if (changed && application.evidence.isNotBlank()) com.brain.behavior.VerificationStatus.PASSED else com.brain.behavior.VerificationStatus.FAILED,
                    listOf(com.brain.behavior.VerificationCheck("revision-fix", changed && application.evidence.isNotBlank(), if (changed) application.evidence else "plano não mudou", "${runId}:fix:$nextAttempt")),
                    listOf("${runId}:fix:$nextAttempt")
                )
            }
            if (!fix.completed) {
                val blocked = finalResult.copy(posExecucao = postExecution.copy(
                    issues = postExecution.issues + fix.issues.map { "revision.fix.$it" } + if (fix.verification?.passed == false) listOf("revision.no-progress") else emptyList(),
                    revisionAttempts = revisionAttempts + RevisionAttemptTrace(attemptRunId, attempt, "FIX_FAILED", fix.issues.joinToString(";"))
                ))
                emit(runId, "revision", "RevisionFixFailed", mapOf("attempt" to attempt.toString(), "issues" to fix.issues.joinToString(";").take(500)))
                return blocked
            }
            val application = requireNotNull(fix.application)
            revisionAttempts += RevisionAttemptTrace(attemptRunId, attempt, "REVISE", application.evidence)
            emit(runId, "revision", "FixVerifyLearnCompleted", mapOf("attempt" to attempt.toString(), "nextAttemptRunId" to "$runId:attempt-$nextAttempt", "evidence" to application.evidence.take(500)))
            currentPlan = application.plan
            attempt = nextAttempt
        }
        error("execution attempts exhausted without terminal result")
    }

    private companion object {
        const val MAX_EXECUTION_ATTEMPTS = 3
        const val MAX_TECHNICAL_RETRIES = 2
        const val TECHNICAL_RETRY_BACKOFF_MS = 50L
    }

    private fun planSignature(plan: PlanoExecucao): String =
        (plan.assumptions.toList().sorted() + plan.ordemDeExecucao.flatMap { listOf(it.id) + it.parametros }).joinToString("\u001f")

    private fun emit(runId: String, taskId: String, type: String, payload: Map<String, String>) {
        val sequence = events.replay().size.toLong()
        events.append(BrainEvent(runId, "android-local", taskId, type, sequence, timestamp = Instant.now(), payload = payload, idempotencyKey = "$runId:$taskId:$type:$sequence"))
        behaviorDiagnostics.record(
            runId = runId,
            taskId = taskId,
            stage = type,
            status = payload["status"] ?: payload["approved"] ?: type,
            detail = payload.entries.joinToString(", ") { "${it.key}=${it.value}" },
            attempt = payload["attempt"]?.toIntOrNull(),
            result = payload["result"] ?: payload["approved"],
            revision = if (type.startsWith("Revision") || type.startsWith("FixVerify")) type else null,
            readiness = payload["readiness"],
            learning = payload["learning"]
        )
    }

    private fun capability(id: String, provided: Set<String>) = CapabilityDefinition(
        id = id,
        name = id,
        description = "capability Android executada pelo ActionGateway",
        category = CapabilityCategory.SANDBOX,
        ownerId = "android-sandbox",
        origin = "android-sandbox",
        providedCapabilities = provided,
        availability = CapabilityAvailability.AVAILABLE,
        provenance = listOf(CapabilityProvenance("android-sandbox", "CapabilityResolver"))
    )
}

private fun <T> runBlockingPlanner(block: suspend () -> T): T {
    var value: T? = null
    var failure: Throwable? = null
    block.startCoroutine(object : Continuation<T> {
        override val context = EmptyCoroutineContext
        override fun resumeWith(result: Result<T>) {
            result.onSuccess { value = it }.onFailure { failure = it }
        }
    })
    failure?.let { throw it }
    return requireNotNull(value)
}
