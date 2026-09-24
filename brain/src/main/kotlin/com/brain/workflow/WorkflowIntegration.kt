package com.brain.workflow

import com.brain.events.BrainEvent
import com.brain.events.EventStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

/** Observador de ciclo de vida. Todos os métodos são opcionais. */
interface WorkflowExecutionListener {
    suspend fun onWorkflowStart(workflowId: String, runId: String) {}
    /** Chamado para qualquer resultado terminal do engine (COMPLETED, FAILED, CANCELLED, TIMED_OUT). */
    suspend fun onWorkflowFinished(workflowId: String, result: WorkflowRunResult) {}
    /** Chamado quando a execução lança exceção antes de produzir um [WorkflowRunResult]. */
    suspend fun onWorkflowError(workflowId: String, runId: String, error: Throwable) {}
}

/**
 * Cola entre o catálogo, o scheduler persistente e o [WorkflowEngine] reais.
 *
 * Esta classe NÃO decide política. Toda autorização e toda execução passam pela [WorkflowRunPort],
 * cujo dono é o PolicyBroker/ActionGateway reais (BrainSandboxController). Não existe lambda
 * `authorize` para o caller preencher, e portanto nenhum default (nem `{ true }`) possível.
 * O que fica aqui são só pré-requisitos estruturais que o PolicyBroker não enxerga
 * ([WorkflowAutomationPolicy]: pin de conteúdo, schedule, o que o documento declara) e o
 * single-flight ([canStartRun], que NÃO é autorização: só evita colidir com o sandbox ocupado).
 *
 * Fluxo:
 *  - [runWorkflow]: execução de um workflow habilitado no catálogo (MANUAL por padrão).
 *  - [startScheduler]/[tick]: consome apenas schedules vencidos via claim/lease do [WorkflowScheduler].
 */
class WorkflowIntegrationService(
    private val catalog: WorkflowCatalog,
    private val scheduler: WorkflowScheduler,
    private val engine: WorkflowEngine,
    private val eventStore: EventStore,
    private val port: WorkflowRunPort,
    /** Single-flight com o sandbox. Falso adia o tick sem consumir nem avançar o schedule. Não autoriza nada. */
    private val canStartRun: () -> Boolean,
    private val owner: String = "workflow-integration",
    private val pollIntervalMs: Long = 5_000,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
    private val clock: () -> Instant = { Instant.now() }
) {
    init {
        require(owner.isNotBlank()) { "owner obrigatório" }
        require(pollIntervalMs > 0) { "pollIntervalMs deve ser positivo" }
    }

    private val listeners = CopyOnWriteArrayList<WorkflowExecutionListener>()
    @Volatile private var schedulerJob: Job? = null

    /** Executa um workflow habilitado. Idempotente por [idempotencyKey] (garantido pelo engine). */
    suspend fun runWorkflow(
        workflowId: String,
        runId: String,
        idempotencyKey: String = "workflow:$workflowId:$runId",
        trigger: WorkflowTrigger = WorkflowTrigger.MANUAL,
        /** Pin de conteúdo aprovado (schedule). Se o documento resolvido agora for outro, o run é bloqueado. */
        expectedContentHash: String? = null
    ): WorkflowRunResult {
        val document = catalog.resolve(workflowId)
            ?: throw NoSuchElementException("workflow não encontrado: $workflowId")
        check(catalog.isEnabled(workflowId)) { "workflow desabilitado: $workflowId" }

        listeners.forEach { it.onWorkflowStart(workflowId, runId) }
        emit(runId, workflowId, "workflow.started", mapOf("version" to document.version, "trigger" to trigger.name))

        val result = try {
            // O pin é conferido contra o documento que VAI executar (não contra uma leitura anterior do tick).
            if (expectedContentHash != null && expectedContentHash != document.contentHash) {
                throw SecurityException("workflow bloqueado: conteúdo mudou desde que o schedule foi registrado")
            }
            // Decisão de autorização: só da porta (PolicyBroker). Bloqueado nunca chega ao engine.
            val preflight = port.preflight(document, runId, trigger)
            if (preflight is WorkflowPreflight.Blocked) throw SecurityException("workflow bloqueado: ${preflight.reason}")
            // engine.run é bloqueante e síncrono; sai da thread do caller/scheduler.
            withContext(Dispatchers.IO) {
                engine.runDocument(
                    document = document,
                    runId = runId,
                    idempotencyKey = idempotencyKey,
                    // Guarda estrutural, não decisão: o node único do documento tem de ser exatamente
                    // workflow.run. A autorização real já veio da porta e é refeita no gateway por passo.
                    authorize = { capability -> capability == WORKFLOW_RUN_CAPABILITY },
                    executeBody = { _, node, attempt -> port.executeBody(document, node, attempt, runId, trigger) }
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emit(runId, workflowId, "workflow.error", mapOf("error" to (e.message ?: e::class.simpleName.orEmpty())))
            listeners.forEach { it.onWorkflowError(workflowId, runId, e) }
            throw e
        }

        emit(
            runId, workflowId, "workflow.finished",
            mapOf("status" to result.status.name, "steps" to result.steps.size.toString(), "error" to (result.error ?: ""))
        )
        listeners.forEach { it.onWorkflowFinished(workflowId, result) }
        return result
    }

    /**
     * Uma iteração do scheduler: faz claim de cada schedule vencido, executa e avança o próximo horário.
     * Exposto (internal) para teste determinístico sem esperar o loop.
     */
    internal suspend fun tick(now: Instant = clock()): List<WorkflowRunResult> {
        val results = mutableListOf<WorkflowRunResult>()
        for (due in scheduler.due(now)) {
            if (!canStartRun()) break // sandbox ocupado: nada é consumido; tenta de novo no próximo tick
            // A validação não pode lançar: id vem do scheduler.json (não validado no load) e um schedule
            // corrompido não pode travar os demais. Falha ao validar = schedule inválido = revogado.
            val refusal: String? = runCatching {
                val document = catalog.resolve(due.id)
                if (document == null) {
                    "workflow não encontrado"
                } else if (!catalog.isEnabled(due.id)) {
                    "workflow desabilitado"
                } else {
                    WorkflowAutomationPolicy.pinRefusal(due, document) ?: WorkflowAutomationPolicy.executionRefusal(document)
                }
            }.getOrElse { "schedule inválido: ${it.message ?: it.javaClass.simpleName}" }
            if (refusal != null) {
                // Schedule que deixou de valer é removido (não roda, não fica em loop); reabilitar registra de novo.
                runCatching { scheduler.unregister(due.id) }
                emit("scheduler", due.id, "workflow.schedule.revoked", mapOf("reason" to refusal))
                continue
            }
            val claimed = runCatching { scheduler.claim(due.id, owner, now) }.getOrNull() ?: continue
            val runId = "scheduled:${due.id}:${claimed.nextRun}"
            val key = "workflow:${due.id}:${claimed.nextRun}"
            val result = try {
                runWorkflow(due.id, runId, key, WorkflowTrigger.SCHEDULED, expectedContentHash = due.contentHash)
            } catch (e: CancellationException) {
                throw e // mantém o claim; o lease expira e outro worker retoma
            } catch (e: Exception) {
                WorkflowRunResult(runId, key, WorkflowStatus.FAILED, emptyList(), e.message)
            }
            // Avança o schedule mesmo em falha para não entrar em loop de retry a cada tick.
            runCatching { scheduler.complete(due.id, owner, now) }
            results += result
        }
        return results
    }

    /** Inicia o loop de polling. Chamado uma vez pelo composition root; idempotente. */
    @Synchronized
    fun startScheduler() {
        if (schedulerJob?.isActive == true) return
        schedulerJob = scope.launch {
            while (isActive) {
                try {
                    tick()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    emit("scheduler", "-", "workflow.scheduler.error", mapOf("error" to (e.message ?: e::class.simpleName.orEmpty())))
                }
                delay(pollIntervalMs)
            }
        }
    }

    @Synchronized
    fun stopScheduler() {
        schedulerJob?.cancel()
        schedulerJob = null
    }

    /** Workflows habilitados que possuem schedule persistido (para a UI). */
    fun scheduledWorkflows(): List<ScheduledWorkflow> =
        catalog.enabled().mapNotNull { scheduler.get(it.id) }

    fun addListener(listener: WorkflowExecutionListener) { listeners.add(listener) }
    fun removeListener(listener: WorkflowExecutionListener) { listeners.remove(listener) }

    private fun emit(runId: String, workflowId: String, type: String, payload: Map<String, String>) {
        eventStore.append(
            BrainEvent(
                runId = runId,
                sessionId = "workflow",
                taskId = workflowId,
                type = type,
                sequence = 0,
                payload = payload + ("workflowId" to workflowId)
            )
        )
    }
}
