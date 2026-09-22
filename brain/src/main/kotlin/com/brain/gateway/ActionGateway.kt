package com.brain.gateway

import com.brain.capability.CapabilityDefinition
import com.brain.capability.CapabilityRegistry
import com.brain.policy.Decision
import com.brain.policy.PolicyBroker
import com.brain.policy.PolicyContext
import com.brain.policy.PolicyDecision
import java.time.Instant
import com.brain.research.ResearchResult
import com.brain.prompt.PromptReasoningTrace
import com.brain.observability.ExecutionTrace
import com.brain.observability.TraceStage
import com.brain.secretary.UserResponse

/** Pedido declarativo; não transporta comando ou shell arbitrário. */
data class ActionRequest(
    val actionId: String,
    val actor: String,
    val capability: String,
    val parameters: Map<String, String> = emptyMap(),
    val resource: String = "",
    val context: PolicyContext,
    val provenance: List<String> = emptyList(),
    val accountId: String? = null
) {
    init {
        require(actionId.isNotBlank()) { "actionId é obrigatório" }
        require(actor.isNotBlank()) { "actor é obrigatório" }
        require(capability.isNotBlank()) { "capability é obrigatória" }
        require(parameters.keys.none { it.isBlank() }) { "nome de parâmetro não pode ser vazio" }
        require(provenance.none { it.isBlank() }) { "proveniência não pode ser vazia" }
    }
}

data class ActionExecution(
    val success: Boolean,
    val result: String? = null,
    val error: String? = null,
    val evidence: List<String> = emptyList(),
    val provenance: List<String> = emptyList(),
    /** Fontes estruturadas usadas pela ação; ficam separadas do conteúdo exibível. */
    val researchSources: List<ResearchResult> = emptyList(),
    /** Trace estruturado do prompt, separado do texto entregue ao gerador. */
    val promptReasoning: PromptReasoningTrace? = null,
    /** Custo real incorrido por esta ação (0.0 = sem custo, ex.: geração local). Nunca inventado — só
     * preenchido por executores que sabem o custo real (ex.: escalonamento para IA paga). */
    val custo: Double = 0.0,
    /** Exceção transitória do executor pode ser repetida pelo orquestrador. */
    val retryable: Boolean = false,
    /** Payload interno para dependências; nunca é conteúdo exibível por si só. */
    val internalPayload: String? = null,
    /** Único texto autorizado a atravessar a fronteira para a UI. */
    val userResponse: UserResponse? = null
)

enum class ActionLifecycle { CREATED, PLANNED, AUTHORIZED, DISPATCHED, RUNNING, SUCCEEDED, FAILED, BLOCKED, RETRYING, CANCELLED }

fun interface ActionExecutor {
    fun execute(request: ActionRequest, capability: CapabilityDefinition, decision: PolicyDecision): ActionExecution
}

data class ActionAuditRecord(
    val actionId: String,
    val actor: String,
    val capability: String,
    val safeParameters: Map<String, String>,
    val policyDecision: String,
    val policyDecisionId: String,
    val startedAt: Instant,
    val finishedAt: Instant,
    val success: Boolean,
    val result: String? = null,
    val error: String? = null,
    val evidence: List<String> = emptyList(),
    val provenance: List<String> = emptyList(),
    val status: ActionLifecycle = if (success) ActionLifecycle.SUCCEEDED else ActionLifecycle.FAILED,
    val inputHash: String? = null,
    val outputReference: String? = null,
    val lifecycle: List<ActionLifecycle> = emptyList(),
    val accountId: String? = null
)

fun interface ActionAuditLog {
    fun append(record: ActionAuditRecord)
}

class InMemoryActionAuditLog : ActionAuditLog {
    private val records = mutableListOf<ActionAuditRecord>()
    private val lock = Any()

    override fun append(record: ActionAuditRecord) = synchronized(lock) { records += record }

    fun all(): List<ActionAuditRecord> = synchronized(lock) { records.toList() }
}

data class ActionGatewayResult(
    val actionId: String,
    val success: Boolean,
    val decision: PolicyDecision,
    val execution: ActionExecution? = null
)

/**
 * Ponto único para ações externas. O gateway descobre a definição, consulta o
 * PolicyBroker, registra início/fim e somente então entrega o pedido declarado
 * ao executor. Ele nunca traduz parâmetros em comandos nem autoriza por conta
 * própria.
 */
class ActionGateway(
    private val registry: CapabilityRegistry,
    private val policy: PolicyBroker,
    private val executor: ActionExecutor,
    private val audit: ActionAuditLog,
    private val clock: () -> Instant = Instant::now,
    private val trace: ExecutionTrace? = null
) {
    private val preActionCheck = PreActionCheck(policy)

    fun execute(request: ActionRequest): ActionGatewayResult {
        val started = clock()
        trace?.record(request.actionId, TraceStage.TASK, "created", request.actionId)
        trace?.record(request.actionId, TraceStage.CAPABILITY, "requested", request.capability)
        val definition = registry.findByCapability(request.capability)
            .firstOrNull { it.status.name == "ACTIVE" }
        val context = request.context.copy(actor = request.actor)
        val decision = policy.authorize(request.actor, request.capability, request.resource, context)
        trace?.record(request.actionId, TraceStage.POLICY, decision.outcome.name, decision.decisionId)
        val safeParameters = redactParameters(request.parameters)

        if (definition == null || decision.decision != Decision.ALLOW || !policy.check(decision, request.resource.takeIf { it.isNotBlank() })) {
            val error = definition?.let { decision.reason } ?: "capability não encontrada no registry"
            val execution = ActionExecution(false, error = error, provenance = request.provenance)
            record(request, safeParameters, decision, started, execution)
            return ActionGatewayResult(request.actionId, false, decision, execution)
        }

        val preCheck = preActionCheck.verify(request, decision)
        if (!preCheck.allowed) {
            val execution = ActionExecution(false, error = "pre-action check bloqueou: ${preCheck.reasons.joinToString("; ")}", provenance = request.provenance)
            record(request, safeParameters, decision, started, execution)
            return ActionGatewayResult(request.actionId, false, decision, execution)
        }

        val execution = runCatching { executor.execute(request, definition, decision) }
            .getOrElse { ActionExecution(false, error = it.message ?: "executor failure", provenance = request.provenance, retryable = true) }
        trace?.record(request.actionId, TraceStage.SANDBOX, if (execution.success) "succeeded" else "failed", request.capability)
        trace?.record(request.actionId, TraceStage.EVIDENCE, if (execution.evidence.isNotEmpty()) "recorded" else "missing", request.actionId)
        record(request, safeParameters, decision, started, execution)
        return ActionGatewayResult(request.actionId, execution.success, decision, execution)
    }

    private fun record(
        request: ActionRequest,
        safeParameters: Map<String, String>,
        decision: PolicyDecision,
        started: Instant,
        execution: ActionExecution
    ) {
        audit.append(
            ActionAuditRecord(
                actionId = request.actionId,
                actor = request.actor,
                capability = request.capability,
                safeParameters = safeParameters,
                policyDecision = decision.outcome.name,
                policyDecisionId = decision.decisionId,
                startedAt = started,
                finishedAt = clock(),
                success = execution.success,
                result = execution.result,
                error = execution.error,
                evidence = execution.evidence,
                provenance = (request.provenance + execution.provenance).distinct(),
                status = if (execution.success) ActionLifecycle.SUCCEEDED else if (decision.decision == Decision.DENY) ActionLifecycle.BLOCKED else ActionLifecycle.FAILED,
                inputHash = request.parameters.entries.sortedBy { it.key }.joinToString("&") { "${it.key}=${it.value}" }.hashCode().toString(16),
                outputReference = execution.result?.takeIf { it.length < 256 },
                lifecycle = listOf(ActionLifecycle.CREATED, ActionLifecycle.PLANNED, ActionLifecycle.DISPATCHED, ActionLifecycle.RUNNING, if (execution.success) ActionLifecycle.SUCCEEDED else ActionLifecycle.FAILED),
                accountId = request.accountId
            )
        )
    }

    private fun redactParameters(parameters: Map<String, String>): Map<String, String> =
        parameters.mapValues { (key, value) ->
            if (SENSITIVE_KEY.matches(key)) "[REDACTED]" else value.take(MAX_AUDIT_VALUE_LENGTH)
        }

    companion object {
        private val SENSITIVE_KEY = Regex("(?i).*(secret|token|password|api[_-]?key|credential).*" )
        private const val MAX_AUDIT_VALUE_LENGTH = 512
    }
}
