package com.brain.planner

import com.brain.policy.ExecutionAuthorization
import com.brain.policy.PolicyDecision

/** Plano que cruzou a fronteira Brain -> Agent com autorização por passo. */
class AuthorizedPlan private constructor(
    val plan: PlanoExecucao,
    val authorizations: Map<String, ExecutionAuthorization>,
    val decisions: Map<String, PolicyDecision> = emptyMap()
) {
    init {
        require(authorizations.keys == plan.passos.map { it.id }.toSet()) {
            "AuthorizedPlan exige uma autorização para cada passo do plano"
        }
        authorizations.forEach { (stepId, authorization) ->
            require(authorization.taskId == stepId) { "autorização não corresponde ao passo '$stepId'" }
        }
        require(decisions.isEmpty() || decisions.keys == authorizations.keys) { "decisões devem cobrir os mesmos passos" }
    }

    companion object {
        fun issue(plan: PlanoExecucao, authorizations: Map<String, ExecutionAuthorization>, decisions: Map<String, PolicyDecision> = emptyMap()): AuthorizedPlan =
            AuthorizedPlan(plan, authorizations.toMap(), decisions.toMap())
    }
}
