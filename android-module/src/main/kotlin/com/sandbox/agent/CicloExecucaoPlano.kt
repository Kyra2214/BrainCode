package com.sandbox.agent

import com.brain.planner.AuthorizedPlan
import com.brain.planner.PassoPlano
import com.brain.planner.PlanoExecucao
import com.brain.dispatch.DispatchTask
import com.brain.dispatch.DispatchStatus
import com.brain.dispatch.Dispatcher
import com.brain.policy.*
import com.brain.qa.EvidenciaComando
import com.brain.qa.ExecutorValidacaoProjeto
import com.brain.qa.ResultadoValidacao
import com.brain.router.AIRouter
import com.brain.router.ApiCatalog
import com.brain.router.DynamicFreeApiCatalog
import com.brain.router.RoutingDecision
import com.brain.router.RoutingProfile


enum class StatusPasso { APROVADO, REPROVADO, NEGADO_PELA_POLICY, AGUARDANDO_APROVACAO, BLOQUEADO_POR_DEPENDENCIA }

data class ResultadoPasso(
    val passoId: String,
    val status: StatusPasso,
    val decisaoPolicy: PolicyDecision? = null,
    val decisaoRouter: RoutingDecision? = null,
    val execucao: AgentSandboxSession.CommandOutcome? = null,
    val evidencias: List<EvidenciaComando> = emptyList(),
    val motivo: String? = null,
    val approvalId: String? = null
)

data class ResultadoCiclo(
    val objetivo: String,
    val runId: String,
    val passos: List<ResultadoPasso>
) {
    val aprovado: Boolean get() = passos.isNotEmpty() && passos.all { it.status == StatusPasso.APROVADO }
}

/** Executa somente planos que já carregam autorizações por passo emitidas pelo Brain. */
class CicloExecucaoPlano(
    private val policyBroker: PolicyBroker,
    private val sandbox: Sandbox,
    private val router: AIRouter,
    private val catalog: ApiCatalog,
    private val validador: ExecutorValidacaoProjeto = ExecutorValidacaoProjeto(),
    private val profiles: List<RoutingProfile> = emptyList(),
    private val approvalStore: ApprovalStore? = null,
    private val dispatcher: Dispatcher? = null
) {
    private val approvedSteps = mutableSetOf<String>()

    /** Fronteira Agent/Sandbox: não aceita PlanoExecucao cru. */
    @Suppress("UNUSED_PARAMETER")
    fun executar(autorizado: AuthorizedPlan, runId: String, actor: String, onPasso: (ResultadoPasso) -> Unit = {}): ResultadoCiclo {
        val plano = autorizado.plan
        val resultados = mutableListOf<ResultadoPasso>()
        val concluidos = mutableSetOf<String>()
        var abortado = false
        for (passo in plano.ordemDeExecucao) {
            if (abortado) {
                resultados += ResultadoPasso(passo.id, StatusPasso.BLOQUEADO_POR_DEPENDENCIA, motivo = "ciclo abortado por passo anterior")
                onPasso(resultados.last())
                continue
            }
            val dependencia = passo.dependeDe.firstOrNull { it !in concluidos }
            if (dependencia != null) {
                resultados += ResultadoPasso(passo.id, StatusPasso.BLOQUEADO_POR_DEPENDENCIA, motivo = "depende de '$dependencia'")
                onPasso(resultados.last())
                continue
            }
            val resultado = processarPasso(passo, autorizado.authorizations.getValue(passo.id), autorizado.decisions[passo.id])
            resultados += resultado
            onPasso(resultado)
            if (resultado.status == StatusPasso.APROVADO) concluidos += passo.id else abortado = true
        }
        return ResultadoCiclo(plano.objetivo, runId, resultados)
    }

    /** Autoriza todos os passos antes de emitir o wrapper aceito pelo Agent. */
    fun autorizarEExecutar(plano: PlanoExecucao, runId: String, actor: String, onPasso: (ResultadoPasso) -> Unit = {}): ResultadoCiclo {
        val authorizations = linkedMapOf<String, ExecutionAuthorization>()
        val decisions = linkedMapOf<String, PolicyDecision>()
        for (passo in plano.ordemDeExecucao) {
            val highRisk = passo.riskClass == com.brain.execution.RiskClass.HIGH || passo.riskClass == com.brain.execution.RiskClass.CRITICAL
            val contexto = PolicyContext(runId, passo.id, actor, riskClass = passo.riskClass, approval = if (highRisk && passo.id !in approvedSteps) ApprovalRequired.USER else ApprovalRequired.NONE)
            val decision = policyBroker.authorize(actor, passo.capacidade, passo.id, contexto)
            if (decision.decision != Decision.ALLOW) {
                val approvalId = if (decision.decision == Decision.ASK) approvalStore?.create(
                    ApprovalRequest(
                        runId = runId,
                        taskId = passo.id,
                        capability = passo.capacidade,
                        resource = passo.id,
                        expiresAt = java.time.Instant.parse(decision.expiresAt)
                    )
                )?.request?.id else null
                val resultadoInicial = ResultadoPasso(
                    passo.id,
                    if (decision.decision == Decision.ASK) StatusPasso.AGUARDANDO_APROVACAO else StatusPasso.NEGADO_PELA_POLICY,
                    decisaoPolicy = decision,
                    motivo = decision.reason,
                    approvalId = approvalId
                )
                val bloqueados = plano.ordemDeExecucao.dropWhile { it.id != passo.id }.drop(1).map {
                    ResultadoPasso(it.id, StatusPasso.BLOQUEADO_POR_DEPENDENCIA, motivo = "ciclo abortado por passo anterior")
                }
                onPasso(resultadoInicial)
                bloqueados.forEach(onPasso)
                return ResultadoCiclo(plano.objetivo, runId, listOf(resultadoInicial) + bloqueados)
            }
            authorizations[passo.id] = ExecutionAuthorization.fromDecision(decision)
                ?: return ResultadoCiclo(plano.objetivo, runId, listOf(ResultadoPasso(passo.id, StatusPasso.NEGADO_PELA_POLICY, decisaoPolicy = decision, motivo = "autorização inválida"))).also { onPasso(it.passos.single()) }
            decisions[passo.id] = decision
        }
        return executar(AuthorizedPlan.issue(plano, authorizations, decisions), runId, actor, onPasso)
    }

    fun retomar(plano: PlanoExecucao, runId: String, actor: String, approvalId: String): ResultadoCiclo {
        val approval = approvalStore?.consume(approvalId)
            ?: return ResultadoCiclo(plano.objetivo, runId, listOf(ResultadoPasso("approval", StatusPasso.NEGADO_PELA_POLICY, motivo = "aprovação inexistente, já consumida ou expirada", approvalId = approvalId)))
        require(approval.request.runId == runId) { "aprovação pertence a outro runId" }
        approvedSteps += approval.request.taskId
        return try { autorizarEExecutar(plano, runId, actor) } finally { approvedSteps -= approval.request.taskId }
    }

    private fun processarPasso(passo: PassoPlano, authorization: ExecutionAuthorization, decision: PolicyDecision?): ResultadoPasso {
        val decisaoRouter = decidirComRefresh(passo)
        dispatcher?.let { modernDispatcher ->
            val dispatch = modernDispatcher.dispatch(
                DispatchTask(
                    taskId = passo.id,
                    step = passo,
                    actor = decision?.actor ?: "android-app",
                    context = PolicyContext(
                        runId = authorization.runId,
                        taskId = passo.id,
                        actor = decision?.actor ?: "android-app",
                        riskClass = passo.riskClass,
                        sandboxRequired = true,
                        networkAllowed = decision?.networkAllowed ?: false,
                        filesystemRoots = decision?.filesystemRoots ?: emptyList(),
                        budget = decision?.budget ?: emptyMap()
                    )
                )
            )
            return ResultadoPasso(
                passo.id,
                if (dispatch.status == DispatchStatus.DISPATCHED) StatusPasso.APROVADO else StatusPasso.REPROVADO,
                decisaoPolicy = dispatch.gateway?.decision ?: decision,
                decisaoRouter = decisaoRouter,
                motivo = dispatch.reason ?: if (dispatch.status == DispatchStatus.DISPATCHED) null else "Dispatcher não executou a capability"
            )
        }
        sandbox.abrirSessao(authorization).use { sessao ->
            val execucao = sessao.rodarCapacidade(passo.parametros)
            if (execucao is AgentSandboxSession.CommandOutcome.Refused) return ResultadoPasso(passo.id, StatusPasso.REPROVADO, decisaoPolicy = decision, decisaoRouter = decisaoRouter, execucao = execucao, motivo = execucao.reason)
            val evidencias = validador.validar(sessao.workspaceHostPath)
            val reprovado = evidencias.any { it.resultado == ResultadoValidacao.FALHOU }
            return ResultadoPasso(passo.id, if (reprovado) StatusPasso.REPROVADO else StatusPasso.APROVADO, decisaoPolicy = decision, decisaoRouter = decisaoRouter, execucao = execucao, evidencias = evidencias, motivo = if (reprovado) "validação encontrou evidência FALHOU" else null)
        }
    }

    /**
     * Se o catálogo for dinâmico, a API escolhida é consultada imediatamente
     * antes do uso. Se a lista mudou, o Router decide novamente. Assim um
     * modelo removido/deprecado não fica preso no seed/cache antigo.
     */
    private fun decidirComRefresh(passo: PassoPlano): RoutingDecision? {
        val papel = passo.papel ?: return null
        val primeira = router.decidir(papel, catalog, profiles) ?: return null
        if (catalog !is DynamicFreeApiCatalog) return primeira
        catalog.refreshProvider(primeira.escolhido.providerId)
        return router.decidir(papel, catalog, profiles)
    }
}
