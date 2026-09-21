package com.sandbox.agent

import com.brain.account.AccountPool
import com.brain.account.AccountRouteDecision
import com.brain.account.AccountRouteRequest
import com.brain.account.AccountRouter
import com.brain.planner.AuthorizedPlan
import com.brain.planner.PassoPlano
import com.brain.planner.PlanoExecucao
import com.brain.dispatch.DispatchTask
import com.brain.dispatch.DispatchStatus
import com.brain.dispatch.Dispatcher
import com.brain.policy.*
import com.brain.secretary.DoorScope
import com.brain.qa.EvidenciaComando
import com.brain.qa.ExecutorValidacaoProjeto
import com.brain.qa.ResultadoValidacao
import com.brain.router.AIRouter
import com.brain.router.ApiCatalog
import com.brain.router.DynamicFreeApiCatalog
import com.brain.router.RoutingDecision
import com.brain.router.RoutingProfile
import com.brain.research.ResearchResult
import com.brain.prompt.PromptReasoningTrace
import com.brain.behavior.CritiqueResult
import com.brain.behavior.CritiqueStatus
import com.brain.behavior.ReadinessReport
import com.brain.behavior.ReadinessStatus
import com.brain.behavior.RevisionDecision
import com.brain.behavior.RevisionAction
import com.brain.behavior.VerificationResult
import com.brain.behavior.VerificationStatus


enum class StatusPasso { APROVADO, REPROVADO, NEGADO_PELA_POLICY, AGUARDANDO_APROVACAO, BLOQUEADO_POR_DEPENDENCIA }

data class ResultadoPasso(
    val passoId: String,
    val status: StatusPasso,
    val decisaoPolicy: PolicyDecision? = null,
    val decisaoRouter: RoutingDecision? = null,
    val execucao: AgentSandboxSession.CommandOutcome? = null,
    val resultado: String? = null,
    val evidencias: List<EvidenciaComando> = emptyList(),
    val motivo: String? = null,
    val approvalId: String? = null,
    val actionId: String? = null,
    /** Custo real reportado pelo executor (ex.: tier da IA usada num escalonamento). 0.0 = sem custo. */
    val custo: Double = 0.0,
    /** Capacidade declarativa do passo (ex.: "prompt.library.write") — permite ao caller (UI)
     * reconhecer o tipo do resultado sem duplicar a lógica do Planner. */
    val capacidade: String? = null,
    val researchSources: List<ResearchResult> = emptyList(),
    val executionEvidence: List<String> = emptyList(),
    val promptReasoning: PromptReasoningTrace? = null
)

data class ResultadoCiclo(
    val objetivo: String,
    val runId: String,
    val passos: List<ResultadoPasso>,
    val posExecucao: ResultadoPosExecucao? = null
) {
    /**
     * O Ciclo direto reporta aprovação técnica quando ainda não recebeu o gate
     * pós-execução. O caminho canônico do controller sempre anexa posExecucao;
     * nesse caminho a aprovação continua exigindo Verification, Critic,
     * Revision e Readiness verdes.
     */
    val concluido: Boolean
        get() = passos.isNotEmpty() && passos.all { it.status == StatusPasso.APROVADO } && (posExecucao == null || posExecucao.aprovado)
    val aprovado: Boolean get() = concluido
    val resposta: String? get() = passos.asSequence().mapNotNull { it.resultado }.lastOrNull()
    val researchSources: List<ResearchResult> get() = passos.flatMap { it.researchSources }.distinctBy { it.url }
    val promptReasoning: PromptReasoningTrace? get() = passos.mapNotNull { it.promptReasoning }.reduceOrNull { a, b -> a.merge(b) }
}

data class ResultadoPosExecucao(
    val verification: VerificationResult,
    val critique: CritiqueResult,
    val revision: RevisionDecision,
    val readiness: ReadinessReport,
    val learningRecorded: Boolean,
    val issues: List<String> = emptyList(),
    val revisionAttempts: List<RevisionAttemptTrace> = emptyList()
) {
    /**
     * Conclusão semântica: nenhum resultado técnico parcial pode promover a UI
     * para READY. Learning é efeito posterior e não é requisito de aprovação,
     * mas verification, critic, revisão e readiness precisam estar aprovados.
     */
    val aprovado: Boolean
        get() = verification.status == VerificationStatus.PASSED && verification.checks.all { it.passed } &&
            critique.status == CritiqueStatus.PASS && revision.action == RevisionAction.ACCEPT &&
            readiness.status == ReadinessStatus.READY && readiness.stages.all { it.passed }
}

data class RevisionAttemptTrace(
    val runId: String,
    val attempt: Int,
    val outcome: String,
    val evidence: String
)

/** Executa somente planos que já carregam autorizações por passo emitidas pelo Brain. */
class CicloExecucaoPlano(
    private val policyBroker: PolicyBroker,
    private val sandbox: Sandbox,
    private val router: AIRouter,
    private val catalog: ApiCatalog,
    private val validador: ExecutorValidacaoProjeto = ExecutorValidacaoProjeto(),
    private val profiles: List<RoutingProfile> = emptyList(),
    private val approvalStore: ApprovalStore? = null,
    private val dispatcher: Dispatcher? = null,
    private val accountRouter: AccountRouter? = null,
    private val accountPools: Map<String, AccountPool> = emptyMap(),
    private val authorizedAccountIds: Set<String> = emptySet()
) {
    private val approvedSteps = mutableSetOf<String>()

    /** Fronteira Agent/Sandbox: não aceita PlanoExecucao cru. */
    @Suppress("UNUSED_PARAMETER")
    fun executar(autorizado: AuthorizedPlan, runId: String, actor: String, onPasso: (ResultadoPasso) -> Unit = {}): ResultadoCiclo {
        val plano = autorizado.plan
        val resultados = mutableListOf<ResultadoPasso>()
        val resultadosPorId = mutableMapOf<String, ResultadoPasso>()
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
            // Context Builder mínimo: resultado textual das dependências já concluídas vira
            // parâmetro extra do passo (ex.: contexto do WebResearch chega ao Prompt Creator).
            val contextoDependencias = passo.dependeDe.mapNotNull { resultadosPorId[it]?.resultado }
            val passoComContexto = if (contextoDependencias.isEmpty()) passo else passo.copy(parametros = passo.parametros + contextoDependencias)
            val resultado = processarPasso(passoComContexto, autorizado.authorizations.getValue(passo.id), autorizado.decisions[passo.id])
            resultados += resultado
            resultadosPorId[passo.id] = resultado
            onPasso(resultado)
            if (resultado.status == StatusPasso.APROVADO) concluidos += passo.id else abortado = true
        }
        return ResultadoCiclo(plano.objetivo, runId, resultados)
    }

    /** Autoriza todos os passos antes de emitir o wrapper aceito pelo Agent. */
    fun autorizarEExecutar(plano: PlanoExecucao, runId: String, actor: String, onPasso: (ResultadoPasso) -> Unit = {}): ResultadoCiclo =
        autorizarEExecutarInterno(plano, runId, actor, null, onPasso)

    fun autorizarEExecutar(plano: PlanoExecucao, runId: String, actor: String, doorScope: DoorScope?, onPasso: (ResultadoPasso) -> Unit = {}): ResultadoCiclo =
        autorizarEExecutarInterno(plano, runId, actor, doorScope, onPasso)

    private fun autorizarEExecutarInterno(plano: PlanoExecucao, runId: String, actor: String, doorScope: DoorScope?, onPasso: (ResultadoPasso) -> Unit): ResultadoCiclo {
        val authorizations = linkedMapOf<String, ExecutionAuthorization>()
        val decisions = linkedMapOf<String, PolicyDecision>()
        for (passo in plano.ordemDeExecucao) {
            val highRisk = passo.riskClass == com.brain.execution.RiskClass.HIGH || passo.riskClass == com.brain.execution.RiskClass.CRITICAL
            val contexto = PolicyContext(
                runId,
                passo.id,
                actor,
                riskClass = passo.riskClass,
                networkAllowed = passo.capacidade == "network.research",
                // Com porta designada, só enxerga as contas que a própria porta libera (nenhuma, por padrão).
                // Repassar a lista global fazia a Policy negar todo passo de porta no app real.
                authorizedAccountIds = doorScope?.visibleAccounts(authorizedAccountIds) ?: authorizedAccountIds,
                approval = if (highRisk && passo.id !in approvedSteps) ApprovalRequired.USER else ApprovalRequired.NONE,
                doorScope = doorScope
            )
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

    fun retomar(plano: PlanoExecucao, runId: String, actor: String, approvalId: String, doorScope: DoorScope? = null): ResultadoCiclo {
        val approval = approvalStore?.consume(approvalId)
            ?: return ResultadoCiclo(plano.objetivo, runId, listOf(ResultadoPasso("approval", StatusPasso.NEGADO_PELA_POLICY, motivo = "aprovação inexistente, já consumida ou expirada", approvalId = approvalId)))
        require(approval.request.runId == runId) { "aprovação pertence a outro runId" }
        approvedSteps += approval.request.taskId
        return try { autorizarEExecutar(plano, runId, actor, doorScope = doorScope) } finally { approvedSteps -= approval.request.taskId }
    }

    private fun processarPasso(passo: PassoPlano, authorization: ExecutionAuthorization, decision: PolicyDecision?): ResultadoPasso {
        val decisaoRouter = decidirComRefresh(passo, decision)
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
                        budget = decision?.budget ?: emptyMap(),
                        authorizedAccountIds = decision?.authorizedAccountIds ?: emptySet(),
                        doorScope = authorization.doorScope ?: decision?.doorScope
                    ),
                    accountId = decisaoRouter?.accountId
                )
            )
            val gatewayExecution = dispatch.gateway?.execution
            if (gatewayExecution?.retryable == true) {
                error(gatewayExecution.error ?: "falha transitória do executor")
            }
            return ResultadoPasso(
                passo.id,
                if (dispatch.status == DispatchStatus.DISPATCHED) StatusPasso.APROVADO else StatusPasso.REPROVADO,
                decisaoPolicy = dispatch.gateway?.decision ?: decision,
                resultado = dispatch.gateway?.execution?.result,
                decisaoRouter = decisaoRouter,
                motivo = dispatch.reason ?: if (dispatch.status == DispatchStatus.DISPATCHED) null else "Dispatcher não executou a capability",
                actionId = "${passo.id}:${passo.id}",
                custo = dispatch.gateway?.execution?.custo ?: 0.0,
                capacidade = passo.capacidade,
                researchSources = dispatch.gateway?.execution?.researchSources ?: emptyList(),
                executionEvidence = dispatch.gateway?.execution?.evidence ?: emptyList(),
                promptReasoning = dispatch.gateway?.execution?.promptReasoning
            )
        }
        sandbox.abrirSessao(authorization).use { sessao ->
            val execucao = sessao.rodarCapacidade(passo.parametros)
            if (execucao is AgentSandboxSession.CommandOutcome.Refused) return ResultadoPasso(passo.id, StatusPasso.REPROVADO, decisaoPolicy = decision, decisaoRouter = decisaoRouter, execucao = execucao, motivo = execucao.reason, capacidade = passo.capacidade)
            val evidencias = validador.validar(sessao.workspaceHostPath)
            val reprovado = evidencias.any { it.resultado == ResultadoValidacao.FALHOU }
            return ResultadoPasso(passo.id, if (reprovado) StatusPasso.REPROVADO else StatusPasso.APROVADO, decisaoPolicy = decision, decisaoRouter = decisaoRouter, execucao = execucao, evidencias = evidencias, motivo = if (reprovado) "validação encontrou evidência FALHOU" else null, capacidade = passo.capacidade)
        }
    }

    private fun decidirComRefresh(passo: PassoPlano, policyDecision: PolicyDecision? = null): RoutingDecision? {
        val papel = passo.papel ?: return null
        val primeira = router.decidir(papel, catalog, profiles) ?: return null
        val refreshed = if (catalog !is DynamicFreeApiCatalog) {
            primeira
        } else {
            catalog.refreshProvider(primeira.escolhido.providerId)
            router.decidir(papel, catalog, profiles) ?: return null
        }
        val pool = accountPools[passo.capacidade] ?: return refreshed
        val accountDecision = accountRouter?.route(
            AccountRouteRequest(
                executionId = passo.id,
                capability = passo.capacidade,
                pool = pool,
                authorizedAccountIds = policyDecision?.authorizedAccountIds ?: emptySet(),
                idempotent = passo.idempotent,
                requestedModelId = refreshed.escolhido.modeloId
            ),
            java.time.Instant.now()
        )
        return when (accountDecision) {
            is AccountRouteDecision.Selected -> refreshed.copy(accountId = accountDecision.accountId)
            else -> refreshed
        }
    }
}
