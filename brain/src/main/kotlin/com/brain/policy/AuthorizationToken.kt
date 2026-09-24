package com.brain.policy

import com.brain.secretary.DoorScope

/**
 * Opaque proof that a PolicyDecision was issued by PolicyBroker and that all
 * execution-relevant fields still match the broker-issued decision.
 */
class AuthorizationToken private constructor(
    private val decisionId: String,
    private val runId: String,
    private val taskId: String,
    private val actor: String,
    private val capability: String,
    private val resource: String,
    private val riskClass: RiskClass,
    private val approvalRequired: ApprovalRequired,
    private val sandboxRequired: Boolean,
    private val networkAllowed: Boolean,
    private val filesystemRoots: List<String>,
    private val budget: Map<String, Long>,
    private val expiresAt: String,
    private val limitsApplied: Boolean,
    private val authorizedAccountIds: Set<String>,
    private val doorScope: DoorScope?
) {
    fun matches(decision: PolicyDecision): Boolean =
        decisionId == decision.decisionId &&
            runId == decision.runId &&
            taskId == decision.taskId &&
            actor == decision.actor &&
            capability == decision.capability &&
            resource == decision.resource &&
            riskClass == decision.riskClass &&
            approvalRequired == decision.approvalRequired &&
            sandboxRequired == decision.sandboxRequired &&
            networkAllowed == decision.networkAllowed &&
            filesystemRoots == decision.filesystemRoots &&
            budget == decision.budget &&
            expiresAt == decision.expiresAt &&
            limitsApplied == decision.limitsApplied &&
            authorizedAccountIds == decision.authorizedAccountIds &&
            doorScope == decision.doorScope

    companion object {
        internal fun issue(decision: PolicyDecision): AuthorizationToken =
            AuthorizationToken(
                decisionId = decision.decisionId,
                runId = decision.runId,
                taskId = decision.taskId,
                actor = decision.actor,
                capability = decision.capability,
                resource = decision.resource,
                riskClass = decision.riskClass,
                approvalRequired = decision.approvalRequired,
                sandboxRequired = decision.sandboxRequired,
                networkAllowed = decision.networkAllowed,
                filesystemRoots = decision.filesystemRoots.toList(),
                budget = decision.budget.toMap(),
                expiresAt = decision.expiresAt,
                limitsApplied = decision.limitsApplied,
                authorizedAccountIds = decision.authorizedAccountIds.toSet(),
                doorScope = decision.doorScope
            )
    }
}
