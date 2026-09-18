package com.brain.dispatch

import com.brain.capability.CapabilityCandidate
import com.brain.capability.CapabilityDiscovery
import com.brain.capability.CapabilityIntent
import com.brain.gateway.ActionGateway
import com.brain.gateway.ActionGatewayResult
import com.brain.gateway.ActionRequest
import com.brain.planner.PassoPlano

/** Tarefa pronta do ExecutionPlan; Dispatcher não redefine objetivo nem plano. */
data class DispatchTask(
    val taskId: String,
    val step: PassoPlano,
    val actor: String,
    val context: com.brain.policy.PolicyContext,
    val accountId: String? = null
) {
    init { require(taskId.isNotBlank()) { "taskId é obrigatório" } }
}

enum class DispatchStatus { DISPATCHED, NO_CANDIDATE, REJECTED }

data class DispatchResult(
    val status: DispatchStatus,
    val taskId: String,
    val selected: CapabilityCandidate? = null,
    val gateway: ActionGatewayResult? = null,
    val reason: String? = null
)

/**
 * Dispatcher executa um plano já criado: consulta Discovery, escolhe o melhor
 * candidato e encaminha uma única ActionRequest ao gateway. Estratégia e
 * decomposição continuam fora dele.
 */
class Dispatcher(
    private val discovery: CapabilityDiscovery,
    private val gateway: ActionGateway
) {
    fun dispatch(task: DispatchTask): DispatchResult {
        val intent = CapabilityIntent(
            objective = task.step.criterioSucesso,
            requiredCapabilities = setOf(task.step.capacidade),
            context = setOf("task:${task.taskId}"),
            maxCandidates = 1
        )
        val discoveryResult = discovery.discover(intent)
        val selected = discoveryResult.candidates.firstOrNull()
            ?: return DispatchResult(
                DispatchStatus.NO_CANDIDATE,
                task.taskId,
                reason = "nenhuma capability candidata para ${task.step.capacidade}"
            )
        val actionId = "${task.taskId}:${task.step.id}"
        val request = ActionRequest(
            actionId = actionId,
            actor = task.actor,
            capability = selected.capability.id,
            parameters = task.step.parametros.mapIndexed { index, value -> "parameter.$index" to value }.toMap(),
            resource = task.step.parametros.firstOrNull { it.startsWith("/") || it.contains("://") }.orEmpty(),
            context = task.context,
            provenance = listOf("plan:${task.taskId}", "discovery:${selected.capability.id}"),
            accountId = task.accountId
        )
        val gatewayResult = gateway.execute(request)
        return DispatchResult(
            status = if (gatewayResult.success) DispatchStatus.DISPATCHED else DispatchStatus.REJECTED,
            taskId = task.taskId,
            selected = selected,
            gateway = gatewayResult,
            reason = gatewayResult.execution?.error
        )
    }
}
