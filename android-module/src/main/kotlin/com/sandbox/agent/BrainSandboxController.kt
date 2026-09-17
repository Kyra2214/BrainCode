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
import com.brain.planner.FastIntentClassifier
import com.brain.policy.PolicyBroker
import com.brain.policy.FileApprovalStore
import com.brain.router.ApiCatalogRegistry
import com.brain.router.DefaultAIRouter
import com.brain.router.InMemoryApiCatalog
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
import com.brain.reasoning.TaskState
import com.brain.execution.OperationalState
import com.brain.execution.Observation
import com.brain.memory.LayeredMemory
import com.brain.memory.Provenance
import com.brain.planner.TreeOfThoughts
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
    private val events: EventStore = InMemoryEventStore()
) {
    private val dynamicCapabilityProviders = capabilityProviders
    private val approvals = FileApprovalStore(File(rootfsDir.parentFile ?: rootfsDir, "approvals.jsonl"))
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
    private val intentClassifier = FastIntentClassifier()
    private val promptRetrieval = promptLibrary?.let { Retrieval(listOf(PromptLibraryRetrievalSource.from(it))) }
    private val apiCatalog = ApiCatalogRegistry.current() ?: InMemoryApiCatalog(emptyList())
    private val bridge = BrainSandboxExecutionBridge(
        CicloExecucaoPlano(
            policyBroker = policy,
            sandbox = sandbox,
            router = DefaultAIRouter(),
            catalog = apiCatalog,
            approvalStore = approvals,
            dispatcher = dispatcher
        )
    )
    private val reasoningEngine = ReasoningEngine()
    private val treeOfThoughts = TreeOfThoughts()
    private val layeredMemory = LayeredMemory()
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
        return bridge.authorizeAndExecute(plano, runId = runId, actor = actor)
    }

    fun executePlan(plano: PlanoExecucao, runId: String = "plan-${System.currentTimeMillis()}"): ResultadoCiclo =
        executeWithEvents(plano, runId) { bridge.authorizeAndExecute(plano, runId = runId, actor = actor) }

    fun resumePlan(plano: PlanoExecucao, runId: String, approvalId: String): ResultadoCiclo =
        executeWithEvents(plano, runId) { bridge.resume(plano, runId = runId, actor = actor, approvalId = approvalId) }

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
        onPasso: (ResultadoPasso) -> Unit = {}
    ): ResultadoCiclo {
        emit(runId, "chat", "TaskCreated", mapOf("objective" to objective.take(500)))
        val classification = intentClassifier.classify(objective)
        val reasoning = reasoningEngine.analyze(objective)
        taskState = TaskState(objective).withReasoning(reasoning)
        layeredMemory.rememberEpisode(objective, Provenance("brain:task-created", confidence = 1.0))
        val planner = com.brain.planner.KeywordPlanner()
        val basePlan = runBlockingPlanner { planner.planejar(classification.intent.objective) }
        if (basePlan.passos.any { it.capacidade == "brain.analyze" } && !apiKeyAvailable()) {
            throw IllegalStateException("Nenhuma chave de API válida está configurada no catálogo. Configure uma chave em Configurações antes de usar o diagnóstico por fallback.")
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
        var cycle: ResultadoCiclo? = null
        durableJobs.run(
            jobId = "job-$runId",
            runId = runId,
            taskId = "plan",
            manifest = WorkflowManifest("brain-plan", "1.0.0", listOf(WorkflowNode("plan", "brain.plan", retryLimit = 0))),
            authorize = { it == "brain.plan" },
            execute = { node, attempt ->
                cycle = executeWithEvents(plan, runId) {
                    bridge.authorizeAndExecute(plan, runId, actor) { passo ->
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
                    }
                }
                WorkflowStepResult(
                    nodeId = node.id,
                    success = cycle?.aprovado == true,
                    attempts = attempt,
                    evidence = cycle?.passos.orEmpty().flatMap { it.evidencias }.map { it.toString() }
                )
            }
        )
        val finalCycle = requireNotNull(cycle) { "Workflow não produziu resultado do plano" }
        val operational = OperationalState(
            objective = objective,
            completed = finalCycle.passos.filter { it.status == StatusPasso.APROVADO }.map { it.passoId },
            observations = finalCycle.passos.map { Observation(it.passoId, it.status == StatusPasso.APROVADO, it.motivo ?: it.status.name) }
        )
        taskState = taskState?.withOperational(operational)
        finalCycle.researchSources.forEach { source ->
            layeredMemory.rememberEvidence(
                source,
                Provenance("web-research:${source.source}", confidence = source.confidence),
                source.validationStatus.name == "VERIFIED"
            )
        }
        return finalCycle
    }

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

    private fun executeWithEvents(plan: PlanoExecucao, runId: String, action: () -> ResultadoCiclo): ResultadoCiclo {
        emit(runId, "plan", "PlanCreated", mapOf("steps" to plan.passos.size.toString()))
        val startedAt = System.nanoTime()
        val result = runCatching { action() }
            .onFailure { emit(runId, "execution", "ExecutionFailed", mapOf("error" to (it.message ?: "unknown").take(500))) }
            .getOrThrow()
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
        emit(runId, "execution", if (result.aprovado) "Delivered" else "ValidationFailed", mapOf("approved" to result.aprovado.toString()))
        return result
    }

    private fun emit(runId: String, taskId: String, type: String, payload: Map<String, String>) {
        val sequence = events.replay().size.toLong()
        events.append(BrainEvent(runId, "android-local", taskId, type, sequence, timestamp = Instant.now(), payload = payload, idempotencyKey = "$runId:$taskId:$type:$sequence"))
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
