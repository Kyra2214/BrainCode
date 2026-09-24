package com.sandbox.agent

import com.brain.gateway.ActionGateway
import com.brain.gateway.ActionRequest
import com.brain.policy.Decision
import com.brain.policy.PolicyBroker
import com.brain.policy.PolicyContext
import com.brain.secretary.CreatePhase
import com.brain.secretary.Door
import com.brain.secretary.DoorScope
import com.brain.secretary.Restriction
import com.brain.workflow.WORKFLOW_RUN_CAPABILITY
import com.brain.workflow.WorkflowAutomationPolicy
import com.brain.workflow.WorkflowDocument
import com.brain.workflow.WorkflowNode
import com.brain.workflow.WorkflowPreflight
import com.brain.workflow.WorkflowRunPort
import com.brain.workflow.WorkflowStepResult
import com.brain.workflow.WorkflowTrigger

/**
 * Única implementação de [WorkflowRunPort]: liga o WorkflowIntegrationService ao PolicyBroker e ao
 * ActionGateway reais do BrainSandboxController (mesma policy, mesmo audit/trace, mesmo EventStore).
 *
 * Decisões de política (ver docs/LEGADO_E_DECISOES.md, "Autorização de workflows automáticos"):
 *  - quem autoriza é só o PolicyBroker; habilitar ou agendar um workflow nunca autoriza nada;
 *  - o run nunca usa `doorScope = null` (que pularia a matriz de Portas): sempre há um escopo explícito;
 *  - qualquer decisão diferente de ALLOW (inclusive ASK) bloqueia. Um run agendado não tem operador para aprovar;
 *  - documento que declara permissions/tools/effects não roda enquanto não houver mapeamento para capabilities;
 *  - run agendado não enxerga contas externas (custo sem operador presente), salvo opt-in explícito.
 */
class GatewayWorkflowRunPort(
    private val policy: PolicyBroker,
    private val gateway: ActionGateway,
    private val actor: String,
    private val authorizedAccountIds: Set<String>,
    private val allowExternalAccountsWhenScheduled: Boolean = false
) : WorkflowRunPort {

    override fun preflight(document: WorkflowDocument, runId: String, trigger: WorkflowTrigger): WorkflowPreflight {
        WorkflowAutomationPolicy.executionRefusal(document)?.let { return WorkflowPreflight.Blocked(it) }
        val decision = policy.authorize(
            actor = actor,
            capability = WORKFLOW_RUN_CAPABILITY,
            resource = resourceOf(document),
            context = contextFor(document, runId, trigger)
        )
        return when {
            decision.decision != Decision.ALLOW ->
                WorkflowPreflight.Blocked("policy ${decision.decision}: ${decision.reason}")
            !policy.check(decision) ->
                WorkflowPreflight.Blocked("decisão de policy expirada ou adulterada")
            else -> WorkflowPreflight.Allowed
        }
    }

    override fun executeBody(
        document: WorkflowDocument,
        node: WorkflowNode,
        attempt: Int,
        runId: String,
        trigger: WorkflowTrigger
    ): WorkflowStepResult {
        val request = ActionRequest(
            actionId = "workflow:$runId:${node.id}:$attempt",
            actor = actor,
            capability = WORKFLOW_RUN_CAPABILITY,
            parameters = mapOf(
                // O corpo é dado não confiável entregue ao executor; o gateway trunca a 512 chars no audit.
                "parameter.0" to document.body,
                "workflowId" to document.id,
                "version" to document.version,
                "contentHash" to document.contentHash,
                "trigger" to trigger.name
            ),
            resource = resourceOf(document),
            context = contextFor(document, runId, trigger),
            provenance = listOf(
                "workflow:${document.id}@${document.version}",
                "trigger:${trigger.name}",
                "source:${document.source.name}"
            )
        )
        val result = gateway.execute(request)
        val execution = result.execution
        return WorkflowStepResult(
            nodeId = node.id,
            success = result.success,
            attempts = attempt,
            output = mapOf("text" to execution?.result),
            error = if (result.success) null else (execution?.error ?: result.decision.reason),
            evidence = execution?.evidence.orEmpty()
        )
    }

    private fun resourceOf(document: WorkflowDocument) = "workflow:${document.id}@${document.version}"

    /**
     * Escopo explícito e restrito: Porta 3 em fase APROVADA (único ponto em que o DoorPolicy libera
     * `workflow.run`), sem web e sem produção de arquivos. Contas externas só se o run for manual
     * (operador presente) ou houver opt-in para agendados.
     */
    private fun scopeFor(trigger: WorkflowTrigger): DoorScope {
        val externalAllowed = trigger == WorkflowTrigger.MANUAL || allowExternalAccountsWhenScheduled
        val restrictions = buildSet {
            add(Restriction.NO_WEB)
            add(Restriction.NO_PRODUCE)
            if (!externalAllowed) add(Restriction.NO_EXTERNAL_APIS)
        }
        return DoorScope(Door.CREATE, CreatePhase.APPROVED, restrictions, externalAccountsAllowed = externalAllowed)
    }

    private fun contextFor(document: WorkflowDocument, runId: String, trigger: WorkflowTrigger): PolicyContext {
        val scope = scopeFor(trigger)
        return PolicyContext(
            runId = runId,
            taskId = document.id,
            actor = actor,
            sandboxRequired = true,
            networkAllowed = false,
            authorizedAccountIds = scope.visibleAccounts(authorizedAccountIds),
            doorScope = scope
        )
    }
}
