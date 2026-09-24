package com.sandbox.agent

import com.brain.capability.CapabilityDefinition
import com.brain.gateway.ActionExecution
import com.brain.gateway.ActionExecutor
import com.brain.gateway.ActionRequest
import com.brain.policy.ExecutionAuthorization
import com.brain.policy.PolicyDecision

/** Executor de produção: Gateway → autorização → AgentSandboxSession → runtime real. */
class BrainActionExecutor(private val sandbox: Sandbox) : ActionExecutor {
    override fun execute(request: ActionRequest, capability: CapabilityDefinition, decision: PolicyDecision): ActionExecution {
        val authorization = ExecutionAuthorization.fromDecision(decision)
            ?: return ActionExecution(false, error = "decisão sem autorização derivável")
        sandbox.abrirSessao(authorization).use { session ->
            val outcome = session.rodarCapacidade(request.parameters.values.toList())
            return when (outcome) {
                is AgentSandboxSession.CommandOutcome.Completed -> ActionExecution(
                    success = outcome.log.succeeded,
                    result = outcome.log.stdout,
                    error = outcome.log.stderr.takeIf { !outcome.log.succeeded },
                    evidence = listOf("sandbox:${outcome.log.executionId}", "termination:${outcome.log.terminationReason}"),
                    provenance = listOf("android:AgentSandboxSession", "capability:${capability.id}")
                )
                is AgentSandboxSession.CommandOutcome.Refused -> ActionExecution(
                    success = false,
                    error = outcome.reason,
                    provenance = listOf("android:AgentSandboxSession", "capability:${capability.id}")
                )
            }
        }
    }
}
