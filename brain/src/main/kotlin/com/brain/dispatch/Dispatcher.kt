package com.brain.dispatch

import com.brain.capability.CapabilityCandidate
import com.brain.capability.CapabilityDiscovery
import com.brain.capability.CapabilityIntent
import com.brain.gateway.ActionGateway
import com.brain.gateway.ActionGatewayResult
import com.brain.gateway.ActionRequest
import com.brain.planner.PassoPlano
import com.brain.router.TipoErro

/** Tarefa pronta do ExecutionPlan; Dispatcher não redefine objetivo nem plano. */
data class DispatchTask(
    val taskId: String,
    val step: PassoPlano,
    val actor: String,
    val context: com.brain.policy.PolicyContext,
    val accountId: String? = null,
    /**
     * Contas alternativas para a mesma capability, tentadas em ordem quando [accountId]
     * (ou a alternativa anterior) falha por um motivo específico de conta (limite atingido,
     * chave inválida, timeout, erro de servidor). Vazio preserva o comportamento anterior:
     * uma única tentativa, com [accountId] ou sem conta nenhuma.
     */
    val accountAlternatives: List<String> = emptyList(),
    /**
     * Quantos candidatos de capability o Discovery deve ranquear e, se necessário, tentar em
     * sequência. 1 preserva o comportamento anterior (um único candidato, sem fallback de
     * provider/capability).
     */
    val maxCandidates: Int = 1
) {
    init {
        require(taskId.isNotBlank()) { "taskId é obrigatório" }
        require(maxCandidates > 0) { "maxCandidates deve ser positivo" }
    }
}

enum class DispatchStatus { DISPATCHED, NO_CANDIDATE, REJECTED }

/**
 * Uma combinação candidato/conta efetivamente tentada numa chamada a [Dispatcher.dispatch].
 * Exposta para que o caller (que conhece o AccountRegistry; o Dispatcher não conhece) possa
 * atualizar saúde de conta por tentativa, sucesso ou falha.
 */
data class DispatchAttempt(
    val candidate: CapabilityCandidate,
    val accountId: String?,
    val gateway: ActionGatewayResult
)

data class DispatchResult(
    val status: DispatchStatus,
    val taskId: String,
    val selected: CapabilityCandidate? = null,
    val gateway: ActionGatewayResult? = null,
    val reason: String? = null,
    /** Todas as combinações candidato/conta tentadas nesta chamada, na ordem em que ocorreram. */
    val attempts: List<DispatchAttempt> = emptyList()
)

/**
 * Dispatcher executa um plano já criado: consulta Discovery, escolhe candidato(s) e
 * encaminha ActionRequest(s) ao gateway.
 *
 * Com [DispatchTask.maxCandidates] = 1 e [DispatchTask.accountAlternatives] vazia — o padrão —
 * dispatcha exatamente uma vez, como antes. Quando o caller pede mais candidatos e/ou informa
 * contas alternativas, o Dispatcher tenta as combinações candidato×conta em ordem até um
 * sucesso ou até esgotar todas: dentro do mesmo candidato, troca de conta em falhas que
 * parecem específicas da conta (limite, chave inválida, timeout, erro de servidor); falhas de
 * policy/requisição inválida não dependem da conta, então já pulam para o próximo candidato.
 *
 * Isso implementa o fallback de conta/provider (ver docs/LEGADO_E_DECISOES.md, Fase 2) sem redesenhar quem decide
 * candidatos (Discovery) nem quem decide contas (AccountRouter) — o Dispatcher continua sem
 * conhecer AccountRegistry; ele só recebe e itera IDs de conta que o caller já escolheu.
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
            maxCandidates = task.maxCandidates
        )
        val discoveryResult = discovery.discover(intent)
        if (discoveryResult.candidates.isEmpty()) {
            return DispatchResult(
                DispatchStatus.NO_CANDIDATE,
                task.taskId,
                reason = "nenhuma capability candidata para ${task.step.capacidade}"
            )
        }

        val accountsToTry = (listOf(task.accountId) + task.accountAlternatives).distinct()
        val attempts = mutableListOf<DispatchAttempt>()
        var lastGateway: ActionGatewayResult? = null
        var lastCandidate: CapabilityCandidate? = null

        for (candidate in discoveryResult.candidates) {
            for (accountId in accountsToTry) {
                val actionId = "${task.taskId}:${task.step.id}"
                val request = ActionRequest(
                    actionId = actionId,
                    actor = task.actor,
                    capability = candidate.capability.id,
                    parameters = task.step.parametros.mapIndexed { index, value -> "parameter.$index" to value }.toMap(),
                    resource = task.step.parametros.firstOrNull { it.startsWith("/") || it.contains("://") }.orEmpty(),
                    context = task.context,
                    provenance = listOf("plan:${task.taskId}", "discovery:${candidate.capability.id}"),
                    accountId = accountId
                )
                val gatewayResult = gateway.execute(request)
                attempts += DispatchAttempt(candidate, accountId, gatewayResult)
                lastGateway = gatewayResult
                lastCandidate = candidate

                if (gatewayResult.success) {
                    return DispatchResult(
                        status = DispatchStatus.DISPATCHED,
                        taskId = task.taskId,
                        selected = candidate,
                        gateway = gatewayResult,
                        attempts = attempts
                    )
                }
                if (!isAccountScopedFailure(gatewayResult.execution?.error)) break
            }
        }

        return DispatchResult(
            status = DispatchStatus.REJECTED,
            taskId = task.taskId,
            selected = lastCandidate,
            gateway = lastGateway,
            reason = lastGateway?.execution?.error,
            attempts = attempts
        )
    }

    /**
     * Decide, só para fins de fallback dentro deste dispatch, se vale tentar a próxima conta
     * do mesmo candidato. Falha de policy ou de requisição inválida se repete com qualquer
     * conta — não vale a pena girar contas nesse caso, é melhor já ir para o próximo candidato.
     * Não é a autoridade de saúde de conta: isso é responsabilidade do caller.
     */
    private fun isAccountScopedFailure(error: String?): Boolean =
        TipoErro.classify(error) !in setOf(TipoErro.POLICY_NEGADA, TipoErro.REQUISICAO_INVALIDA)
}
