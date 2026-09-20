package com.brain.policy

import com.brain.secretary.DoorScope

/**
 * Resultado possível de uma autorização. Espelha
 * reference/braincode-python/brain_runtime/models.py::Decision.
 */
enum class Decision { ALLOW, ASK, DENY }

/** Resultado arquitetural normalizado; ASK permanece para compatibilidade. */
enum class PolicyOutcome { ALLOW, ALLOW_WITH_LIMITS, DENY, REQUIRE_APPROVAL, SANDBOX_ONLY }

/**
 * Nível de aprovação humana exigido antes de uma capacidade poder ser
 * executada, mesmo quando a Policy já autorizaria sozinha (decision = ASK).
 */
enum class ApprovalRequired { NONE, USER, ADMIN }

typealias RiskClass = com.brain.execution.RiskClass

data class PolicyContext(
    val runId: String,
    val taskId: String,
    val actor: String,
    val riskClass: RiskClass = RiskClass.LOW,
    val approval: ApprovalRequired = ApprovalRequired.NONE,
    val sandboxRequired: Boolean = true,
    val networkAllowed: Boolean = false,
    val filesystemRoots: List<String> = emptyList(),
    val budget: Map<String, Long> = emptyMap(),
    val ttlSeconds: Int = 300,
    val expiresAt: String? = null,
    val dataClassifications: Set<String> = emptySet(),
    val environment: String = "sandbox",
    /** Contas que a Policy permite para esta execução; vazio significa nenhuma conta externa. */
    val authorizedAccountIds: Set<String> = emptySet(),
    /** Escopo designado pelo Secretário; nulo preserva o contrato legado. */
    val doorScope: DoorScope? = null
) {
    init {
        require(runId.isNotBlank()) { "runId não pode ser vazio" }
        require(taskId.isNotBlank()) { "taskId não pode ser vazio" }
        require(actor.isNotBlank()) { "actor não pode ser vazio" }
        require(dataClassifications.none { it.isBlank() }) { "classificação de dado não pode ser vazia" }
        require(environment.isNotBlank()) { "environment não pode ser vazio" }
        require(authorizedAccountIds.none { it.isBlank() }) { "accountId autorizado não pode ser vazio" }
    }
}

/**
 * Registro imutável de uma decisão de autorização.
 *
 * [authorizationToken] é emitido exclusivamente pelo PolicyBroker. Isso é
 * importante porque PolicyDecision é uma data class e, portanto, possui
 * copy(): alterar capability/resource via copy() não pode produzir uma nova
 * autorização válida, pois o token continua vinculado aos valores originais.
 */
data class PolicyDecision(
    val decisionId: String,
    val runId: String,
    val taskId: String,
    val actor: String,
    val capability: String,
    val riskClass: RiskClass,
    val decision: Decision,
    val approvalRequired: ApprovalRequired,
    val sandboxRequired: Boolean,
    val networkAllowed: Boolean,
    val filesystemRoots: List<String>,
    val budget: Map<String, Long>,
    val expiresAt: String,
    val reason: String,
    val resource: String = "",
    val authorizationToken: AuthorizationToken? = null,
    val limitsApplied: Boolean = false,
    val authorizedAccountIds: Set<String> = emptySet(),
    val doorScope: DoorScope? = null
) {
    val outcome: PolicyOutcome
        get() = when {
            decision == Decision.DENY -> PolicyOutcome.DENY
            decision == Decision.ASK -> PolicyOutcome.REQUIRE_APPROVAL
            limitsApplied -> PolicyOutcome.ALLOW_WITH_LIMITS
            sandboxRequired -> PolicyOutcome.SANDBOX_ONLY
            else -> PolicyOutcome.ALLOW
        }

    val isExpired: Boolean
        get() = java.time.Instant.now().isAfter(java.time.Instant.parse(expiresAt))
}
