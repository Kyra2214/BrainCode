package com.brain.policy

import com.brain.execution.ResourceBudget
import com.brain.secretary.DoorScope

/**
 * O terreno preparado que o Brain entrega pro Sandbox antes de qualquer
 * agente trabalhar. A autorização só pode nascer de uma decisão ALLOW que
 * carregue o token opaco emitido pelo PolicyBroker.
 */
class ExecutionAuthorization private constructor(
    val decisionId: String,
    val runId: String,
    val taskId: String,
    val actor: String,
    val capability: String,
    val riskClass: RiskClass,
    val networkAllowed: Boolean,
    val filesystemRoots: List<String>,
    val budget: ResourceBudget,
    val expiresAt: String,
    val doorScope: DoorScope? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ExecutionAuthorization) return false
        return decisionId == other.decisionId && runId == other.runId && taskId == other.taskId &&
            actor == other.actor && capability == other.capability && riskClass == other.riskClass &&
            networkAllowed == other.networkAllowed && filesystemRoots == other.filesystemRoots &&
            budget == other.budget && expiresAt == other.expiresAt && doorScope == other.doorScope
    }

    override fun hashCode(): Int =
        listOf(decisionId, runId, taskId, actor, capability, riskClass, networkAllowed, filesystemRoots, budget, expiresAt, doorScope).hashCode()

    override fun toString(): String =
        "ExecutionAuthorization(decisionId=$decisionId, runId=$runId, taskId=$taskId, actor=$actor, " +
            "capability=$capability, riskClass=$riskClass, networkAllowed=$networkAllowed, " +
            "filesystemRoots=$filesystemRoots, budget=$budget, expiresAt=$expiresAt, doorScope=$doorScope)"

    companion object {
        /**
         * Única forma de obter uma ExecutionAuthorization. Além de ALLOW e
         * TTL, exige o token emitido pelo PolicyBroker e rejeita qualquer
         * PolicyDecision adulterada via copy().
         */
        fun fromDecision(decision: PolicyDecision): ExecutionAuthorization? {
            if (decision.decision != Decision.ALLOW || decision.isExpired) return null
            val token = decision.authorizationToken ?: return null
            if (!token.matches(decision)) return null
            return ExecutionAuthorization(
                decisionId = decision.decisionId,
                runId = decision.runId,
                taskId = decision.taskId,
                actor = decision.actor,
                capability = decision.capability,
                riskClass = decision.riskClass,
                networkAllowed = decision.networkAllowed,
                filesystemRoots = decision.filesystemRoots,
                budget = decision.budget.toResourceBudget(),
                expiresAt = decision.expiresAt,
                doorScope = decision.doorScope
            )
        }
    }
}

private fun Map<String, Long>.toResourceBudget(): ResourceBudget = ResourceBudget(
    maxCpuMillis = this["cpu_ms"],
    maxMemoryBytes = this["memory_bytes"],
    maxOutputBytes = this["output_bytes"],
    maxArtifactBytes = this["artifact_bytes"]
)
