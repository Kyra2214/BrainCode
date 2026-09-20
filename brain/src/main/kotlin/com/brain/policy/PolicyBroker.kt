package com.brain.policy

import com.brain.capability.CapabilityRegistry
import com.brain.secretary.DoorPolicy
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Autoridade única de autorização. Catálogos, routers e dispatchers nunca
 * autorizam por conta própria — todos passam por aqui.
 *
 * Deny-by-default: qualquer combinação de actor/capability/contexto que não
 * passe explicitamente por todas as checagens abaixo é negada.
 */
class PolicyBroker(
    allowedCapabilities: Collection<String> = emptyList(),
    actorCapabilities: Map<String, Collection<String>> = emptyMap(),
    private val capabilityRegistry: CapabilityRegistry? = null
) {
    private val allowed: Set<String> = allowedCapabilities.toSet()
    private val actors: Map<String, Set<String>> = actorCapabilities.mapValues { it.value.toSet() }

    fun authorize(actor: String, capability: String, resource: String, context: PolicyContext): PolicyDecision {
        var decision = Decision.DENY
        var reason: String

        when {
            context.ttlSeconds <= 0 -> reason = "policy TTL must be positive"
            context.budget.values.any { it < 0 } -> reason = "budget values cannot be negative"
            context.doorScope != null && !DoorPolicy.allows(context.doorScope, capability) ->
                reason = "capability '$capability' is outside the designated door ${context.doorScope.door}"
            context.doorScope != null && context.authorizedAccountIds.isNotEmpty() && !context.doorScope.externalAccountsAllowed ->
                reason = "external accounts are not allowed for door ${context.doorScope.door}"
            !isRegistered(capability) -> reason = "capability '$capability' is not registered"
            capability !in (actors[actor] ?: emptySet()) -> reason = "actor '$actor' is not authorized for '$capability'"
            !context.sandboxRequired && context.riskClass !in setOf(RiskClass.LOW, RiskClass.READ_ONLY) ->
                reason = "sandbox is mandatory for non-low-risk capability"
            capability.startsWith("network") && !context.networkAllowed ->
                reason = "network access is not allowed by policy"
            capability.startsWith("filesystem") && context.filesystemRoots.isEmpty() ->
                reason = "filesystem capability requires an explicit root"
            context.environment.equals("production", ignoreCase = true) &&
                context.riskClass in setOf(RiskClass.HIGH, RiskClass.CRITICAL) && !context.sandboxRequired ->
                reason = "high-risk production action must be sandboxed"
            context.dataClassifications.any { it.equals("restricted", ignoreCase = true) } &&
                context.riskClass !in setOf(RiskClass.READ_ONLY, RiskClass.LOW) &&
                context.approval == ApprovalRequired.NONE -> {
                decision = Decision.ASK
                reason = "restricted data requires explicit approval for medium or higher risk"
            }
            context.expiresAt != null && !Instant.now().isBefore(Instant.parse(context.expiresAt)) ->
                reason = "policy context has expired"
            context.approval != ApprovalRequired.NONE -> {
                decision = Decision.ASK
                reason = "explicit ${context.approval} approval is required"
            }
            else -> {
                decision = Decision.ALLOW
                reason = "registered capability authorized for resource '$resource'"
            }
        }

        val expires = Instant.now().plus(maxOf(1, context.ttlSeconds).toLong(), ChronoUnit.SECONDS)
        val limitsApplied = decision == Decision.ALLOW && (
            context.budget.isNotEmpty() || context.filesystemRoots.isNotEmpty() ||
                context.ttlSeconds < 300 || context.networkAllowed
            )
        val unsigned = PolicyDecision(
            decisionId = "decision_${UUID.randomUUID()}",
            runId = context.runId,
            taskId = context.taskId,
            actor = actor,
            capability = capability,
            riskClass = context.riskClass,
            decision = decision,
            approvalRequired = context.approval,
            sandboxRequired = context.sandboxRequired,
            networkAllowed = context.networkAllowed,
            filesystemRoots = context.filesystemRoots,
            budget = context.budget,
            expiresAt = expires.toString(),
            reason = reason,
            resource = resource,
            limitsApplied = limitsApplied,
            authorizedAccountIds = context.authorizedAccountIds.toSet(),
            doorScope = context.doorScope
        )
        return unsigned.copy(authorizationToken = AuthorizationToken.issue(unsigned))
    }

    /**
     * Confirma que uma decisão ALLOW ainda é válida e, quando fornecida, que
     * o recurso permanece dentro dos roots autorizados. O token também deve
     * continuar correspondendo à decisão original, impedindo adulteração via
     * PolicyDecision.copy().
     */
    fun check(decision: PolicyDecision, resource: String? = null): Boolean {
        if (decision.decision != Decision.ALLOW || decision.isExpired) return false
        val token = decision.authorizationToken ?: return false
        if (!token.matches(decision)) return false
        if (resource != null && decision.filesystemRoots.isNotEmpty()) {
            val path = java.io.File(resource).canonicalFile
            val dentroDeAlgumRoot = decision.filesystemRoots.any { root ->
                val rootFile = java.io.File(root).canonicalFile
                path == rootFile || path.toPath().startsWith(rootFile.toPath())
            }
            if (!dentroDeAlgumRoot) return false
        }
        return true
    }

    fun withActorCapability(actor: String, capability: String): PolicyBroker {
        val novosActors = actors.mapValues { it.value.toMutableSet() }.toMutableMap()
        novosActors.getOrPut(actor) { mutableSetOf() }.add(capability)
        return PolicyBroker(allowed + capability, novosActors, capabilityRegistry)
    }

    fun withCapabilityRegistry(registry: CapabilityRegistry): PolicyBroker =
        PolicyBroker(allowed, actors, registry)

    private fun isRegistered(capability: String): Boolean =
        if (capabilityRegistry != null) {
            capabilityRegistry.findByCapability(capability).any { it.status == com.brain.capability.CapabilityStatus.ACTIVE }
        } else {
            capability in allowed
        }
}
