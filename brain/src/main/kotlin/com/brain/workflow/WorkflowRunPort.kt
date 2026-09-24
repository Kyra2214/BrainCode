package com.brain.workflow

/** Única capability pela qual qualquer execução de workflow passa pelo gateway autorizado. */
const val WORKFLOW_RUN_CAPABILITY = "workflow.run"

/**
 * Origem da execução. Só decide qual política se aplica (ver [WorkflowAutomationPolicy]);
 * nunca concede nada por si.
 */
enum class WorkflowTrigger { MANUAL, SCHEDULED }

/** Resultado do pré-voo de autorização. Não há caminho para "permitir por padrão". */
sealed class WorkflowPreflight {
    object Allowed : WorkflowPreflight()
    data class Blocked(val reason: String) : WorkflowPreflight()
}

/**
 * Porta de execução de workflows.
 *
 * Quem implementa é o dono do PolicyBroker/ActionGateway reais (hoje BrainSandboxController).
 * Catálogo, scheduler e [WorkflowIntegrationService] NÃO autorizam nada: só perguntam à porta.
 * Isso substitui as lambdas `authorize`/`executeBody` que o caller precisava fornecer — e que
 * permitiam, por construção, passar `{ true }`.
 */
interface WorkflowRunPort {
    /**
     * Decide se o run pode começar. Deve passar pelo PolicyBroker; qualquer resultado que
     * não seja ALLOW (incluindo ASK) vira [WorkflowPreflight.Blocked].
     */
    fun preflight(document: WorkflowDocument, runId: String, trigger: WorkflowTrigger): WorkflowPreflight

    /** Executa o node passando pelo ActionGateway (autoriza de novo, audita e só então executa). */
    fun executeBody(
        document: WorkflowDocument,
        node: WorkflowNode,
        attempt: Int,
        runId: String,
        trigger: WorkflowTrigger
    ): WorkflowStepResult
}
