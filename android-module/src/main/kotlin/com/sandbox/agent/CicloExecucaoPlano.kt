package com.sandbox.agent

import com.brain.account.AccountFailureClass
import com.brain.account.AccountPool
import com.brain.account.AccountRegistry
import com.brain.account.AccountRouteDecision
import com.brain.account.AccountRouteRequest
import com.brain.account.AccountRouter
import com.brain.planner.AuthorizedPlan
import com.brain.planner.PassoPlano
import com.brain.planner.PlanoExecucao
import com.brain.dispatch.DispatchAttempt
import com.brain.dispatch.DispatchTask
import com.brain.dispatch.DispatchResult
import com.brain.dispatch.DispatchStatus
import com.brain.dispatch.Dispatcher
import com.brain.memory.Experiencia
import com.brain.memory.ExperienceMemory
import com.brain.memory.ResultadoExperiencia
import com.brain.runtime.NoopRuntimeDoctor
import com.brain.runtime.RuntimeDoctor
import com.brain.policy.*
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import com.brain.secretary.DoorScope
import com.brain.qa.EvidenciaComando
import com.brain.qa.ExecutorValidacaoProjeto
import com.brain.qa.ResultadoValidacao
import com.brain.router.AIRouter
import com.brain.router.ApiCatalog
import com.brain.router.DynamicFreeApiCatalog
import com.brain.router.RoutingDecision
import com.brain.router.RoutingProfile
import com.brain.router.TipoErro
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
import com.brain.validation.ValidationResult
import com.brain.secretary.UserResponse
import com.brain.secretary.Door


enum class StatusPasso { APROVADO, REPROVADO, NEGADO_PELA_POLICY, AGUARDANDO_APROVACAO, BLOQUEADO_POR_DEPENDENCIA }

data class ResultadoPasso(
    val passoId: String,
    val status: StatusPasso,
    val decisaoPolicy: PolicyDecision? = null,
    val decisaoRouter: RoutingDecision? = null,
    val execucao: AgentSandboxSession.CommandOutcome? = null,
    val resultado: String? = null,
    /** Conteúdo interno para dependências; não é uma resposta de usuário. */
    val payloadInterno: String? = null,
    val userResponse: UserResponse? = null,
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
    /** Somente respostas liberadas pelo Secretary podem ser consumidas pela UI. */
    val resposta: String? get() = passos.asSequence().mapNotNull { it.userResponse?.text }.lastOrNull()
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
    val revisionAttempts: List<RevisionAttemptTrace> = emptyList(),
    val selfE2E: List<ValidationResult> = emptyList(),
    val doorE2E: ValidationResult? = null
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
    private val authorizedAccountIds: Set<String> = emptySet(),
    /**
     * Fallback de conta/provider (item de backlog fechado — ver LEGADO_E_DECISOES.md, Fase 2):
     * quando presente, cada tentativa de dispatch (inclusive fallback para conta alternativa)
     * atualiza a saúde da conta correspondente. null = não atualiza saúde (comportamento anterior).
     */
    private val accountRegistry: AccountRegistry? = null,
    /**
     * Quantos candidatos de capability o Dispatcher deve considerar por passo. 1 preserva o
     * comportamento anterior (um único candidato). Combinado com [accountPools], permite ao
     * Dispatcher girar entre contas alternativas do mesmo pool e, se essas se esgotarem, entre
     * candidatos de capability diferentes — sem precisar de um segundo coordenador.
     */
    private val capabilityFallbackCandidates: Int = 3,
    /** Aprendizado por passo (Fase 2 — ver LEGADO_E_DECISOES.md); null = não registra. */
    private val memory: ExperienceMemory? = null,
    /** Diagnóstico/auto-reparo pós-falha (Fase 2); Noop até existir implementação real. */
    private val runtimeDoctor: RuntimeDoctor = NoopRuntimeDoctor,
    /** Tentativas extras (além da primeira) para passos idempotentes cujo executor sinaliza falha transitória. */
    private val maxRetries: Int = 1,
    private val retryBackoffMs: (Int) -> Long = { retry -> (100L * (1L shl (retry - 1).coerceAtMost(4))).coerceAtMost(1600L) },
    private val sleeper: (Long) -> Unit = Thread::sleep
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
            val contextoDependencias = passo.dependeDe.mapNotNull { resultadosPorId[it]?.payloadInterno ?: resultadosPorId[it]?.resultado }
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
        val resolucao = decidirComRefresh(passo, decision)
        val decisaoRouter = resolucao.routing
        dispatcher?.let { modernDispatcher ->
            val startedAt = System.currentTimeMillis()
            val retryLimit = if (passo.idempotent) maxRetries else 0
            var dispatch: DispatchResult? = null
            var tentativas = 0
            val tentativasContas = mutableListOf<DispatchAttempt>()
            for (retry in 0..retryLimit) {
                tentativas++
                dispatch = modernDispatcher.dispatch(
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
                        accountId = decisaoRouter?.accountId,
                        accountAlternatives = resolucao.accountAlternatives,
                        maxCandidates = capabilityFallbackCandidates
                    )
                )
                tentativasContas += dispatch?.attempts.orEmpty()
                val retryable = dispatch?.gateway?.execution?.retryable == true
                if (!retryable) break
                if (retry < retryLimit) sleeper(retryBackoffMs(retry + 1).coerceIn(0L, 1600L))
            }
            registrarSaudeContas(tentativasContas)
            val dispatchFinal = dispatch!!
            val gatewayExecution = dispatchFinal.gateway?.execution
            val sucesso = dispatchFinal.status == DispatchStatus.DISPATCHED
            recordExperience(
                runId = authorization.runId,
                stepId = passo.id,
                objetivo = passo.criterioSucesso,
                estrategia = decisaoRouter?.escolhido?.let { "${it.providerId}/${it.modeloId}" } ?: "local",
                sucesso = sucesso,
                tentativas = tentativas,
                elapsedMs = System.currentTimeMillis() - startedAt,
                erro = gatewayExecution?.error
            )
            if (!sucesso) {
                val diagnosis = runtimeDoctor.diagnose(authorization.runId, passo.id, gatewayExecution?.error)
                if (!diagnosis.healthy) runtimeDoctor.repair(authorization.runId, passo.id, diagnosis)
            }
            val chatResponse = if (passo.capacidade == "chat.respond" && sucesso) {
                gatewayExecution?.userResponse ?: gatewayExecution?.result?.takeIf { it.isNotBlank() }?.let {
                    UserResponse(
                        text = it,
                        evidence = gatewayExecution.evidence,
                        requestId = "${authorization.runId}:${passo.id}",
                        conversationId = authorization.runId
                    )
                }
            } else gatewayExecution?.userResponse
            val chatEvidence = if (passo.capacidade == "chat.respond" && chatResponse != null && authorization.doorScope?.door == Door.CHAT) {
                (gatewayExecution?.evidence.orEmpty() + "chat:secretary:accept" + "chat:request:${passo.id}").distinct()
            } else gatewayExecution?.evidence.orEmpty()
            return ResultadoPasso(
                passo.id,
                if (sucesso) StatusPasso.APROVADO else StatusPasso.REPROVADO,
                decisaoPolicy = dispatchFinal.gateway?.decision ?: decision,
                resultado = if (passo.capacidade == "chat.respond") {
                    chatResponse?.text
                } else {
                    gatewayExecution?.result ?: gatewayExecution?.internalPayload
                },
                payloadInterno = gatewayExecution?.internalPayload,
                userResponse = chatResponse,
                decisaoRouter = decisaoRouter,
                motivo = dispatchFinal.reason ?: if (sucesso) null else "Dispatcher não executou a capability",
                actionId = "${passo.id}:${passo.id}",
                custo = gatewayExecution?.custo ?: 0.0,
                capacidade = passo.capacidade,
                researchSources = gatewayExecution?.researchSources ?: emptyList(),
                executionEvidence = chatEvidence,
                promptReasoning = gatewayExecution?.promptReasoning
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

    /**
     * [accountAlternatives] são as demais contas elegíveis do mesmo pool, na ordem de
     * prioridade do AccountRouter — repassadas ao Dispatcher para fallback (ver
     * LEGADO_E_DECISOES.md, Fase 2); vazio quando não há pool ou não há alternativa.
     */
    private data class RouterResolution(val routing: RoutingDecision?, val accountAlternatives: List<String> = emptyList())

    private fun decidirComRefresh(passo: PassoPlano, policyDecision: PolicyDecision? = null): RouterResolution {
        val papel = passo.papel ?: return RouterResolution(null)
        val primeira = router.decidir(papel, catalog, profiles) ?: return RouterResolution(null)
        val refreshed = if (catalog !is DynamicFreeApiCatalog) {
            primeira
        } else {
            catalog.refreshProvider(primeira.escolhido.providerId)
            router.decidir(papel, catalog, profiles) ?: return RouterResolution(null)
        }
        val pool = accountPools[passo.capacidade] ?: return RouterResolution(refreshed)
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
            is AccountRouteDecision.Selected -> RouterResolution(
                refreshed.copy(accountId = accountDecision.accountId),
                accountDecision.alternatives.map { it.accountId }
            )
            else -> RouterResolution(refreshed)
        }
    }

    /**
     * Fallback de conta/provider (Fase 2 — ver LEGADO_E_DECISOES.md): a única cobertura disso
     * antes era o BrainExecutionCoordinator legado. Aqui a saúde é atualizada para toda conta
     * efetivamente tentada pelo Dispatcher nesta chamada (inclusive alternativas usadas em
     * fallback), sucesso ou falha — sem isso, uma conta com chave inválida ou rate limit nunca
     * fica marcada como não saudável no caminho real, mesmo com fallback funcionando.
     */
    private fun registrarSaudeContas(tentativas: List<DispatchAttempt>) {
        val registry = accountRegistry ?: return
        for (tentativa in tentativas) {
            val accountId = tentativa.accountId ?: continue
            val current = registry.find(accountId) ?: continue
            val now = java.time.Instant.now()
            val next = if (tentativa.gateway.success) {
                current.health.afterSuccess(now)
            } else {
                current.health.afterFailure(TipoErro.classify(tentativa.gateway.execution?.error).toAccountFailure(), now)
            }
            runCatching { registry.updateHealth(accountId, next) }
        }
    }

    private fun TipoErro.toAccountFailure(): AccountFailureClass = when (this) {
        TipoErro.LIMITE_ATINGIDO -> AccountFailureClass.RATE_LIMIT
        TipoErro.CHAVE_INVALIDA -> AccountFailureClass.AUTH_FAILURE
        TipoErro.TIMEOUT -> AccountFailureClass.TIMEOUT
        TipoErro.ERRO_SERVIDOR -> AccountFailureClass.TEMPORARY_PROVIDER_FAILURE
        TipoErro.POLICY_NEGADA -> AccountFailureClass.POLICY_DENIED
        TipoErro.REQUISICAO_INVALIDA -> AccountFailureClass.INVALID_REQUEST
        TipoErro.DESCONHECIDO -> AccountFailureClass.UNKNOWN
    }

    /**
     * Fase 2 (ver LEGADO_E_DECISOES.md): registra uma [Experiencia] por passo despachado,
     * mesmo que [memory] seja nulo (no-op nesse caso). Falha é dado, não é ausência de dado.
     */
    private fun recordExperience(
        runId: String,
        stepId: String,
        objetivo: String,
        estrategia: String,
        sucesso: Boolean,
        tentativas: Int,
        elapsedMs: Long,
        erro: String?
    ) {
        val target = memory ?: return
        val resultado = when {
            !sucesso -> ResultadoExperiencia.FALHA
            tentativas > 1 -> ResultadoExperiencia.CORRIGIDO_APOS_FALHA
            else -> ResultadoExperiencia.SUCESSO
        }
        val experiencia = Experiencia(
            id = "$runId:$stepId",
            tarefaId = stepId,
            problema = objetivo,
            estrategiaUsada = estrategia,
            promptUsado = null,
            resultado = resultado,
            custoEstimado = 0.0,
            tempoTotalMs = elapsedMs,
            erros = erro?.let { listOf(safeError(it)) } ?: emptyList(),
            registradoEm = java.time.Instant.now()
        )
        runCatching { await { target.registrar(experiencia) } }
    }

    private fun safeError(error: String?): String = error.orEmpty()
        .replace(Regex("(?i)(bearer\\s+|api[_-]?key|token|password|secret)[=: ]+[^,; ]+"), "[REDACTED]")
        .take(512)
        .ifBlank { "failed" }

    private fun <T> await(block: suspend () -> T): T {
        var completed: Result<T>? = null
        block.startCoroutine(object : Continuation<T> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(result: Result<T>) { completed = result }
        })
        return completed!!.getOrThrow()
    }
}
