package com.brain.gateway

import com.brain.policy.Decision
import com.brain.policy.PolicyBroker
import com.brain.policy.PolicyDecision

data class PreActionCheckResult(
    val allowed: Boolean,
    val reasons: List<String> = emptyList()
)

/** Checagem antes da ação: falha fechada e sem executar efeitos externos. */
class PreActionCheck(
    private val policy: PolicyBroker
) {
    fun verify(request: ActionRequest, decision: PolicyDecision): PreActionCheckResult {
        val reasons = buildList {
            if (request.provenance.isEmpty()) add("ação sem proveniência")
            if (decision.decision != Decision.ALLOW) add("política não autorizou a ação")
            if (!policy.check(decision, request.resource.takeIf { it.isNotBlank() })) add("decisão expirada, adulterada ou fora do escopo")
            if (request.capability != decision.capability) add("capability divergente da decisão")
            if (request.actor != decision.actor) add("actor divergente da decisão")
        }
        return PreActionCheckResult(reasons.isEmpty(), reasons)
    }
}
