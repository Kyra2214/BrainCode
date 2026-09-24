package com.brain.secretary

import com.brain.validation.FindingOwner
import com.brain.validation.ValidationResult
import com.brain.validation.ValidationStatus

enum class ValidationRouteAction {
    CORRECT_AGENT,
    ASK_USER,
    REPORT_POLICY,
    RETRY_INFRA,
    ADVANCE
}

data class ValidationRoute(
    val action: ValidationRouteAction,
    val responsibleAgentId: String? = null,
    val taskId: String? = null,
    val reason: String
)

/**
 * O Secretário decide para onde um finding deve voltar.
 * Não executa a correção e não concede autorização; apenas fecha o roteamento
 * entre validação, responsável e próxima ação.
 */
object SecretaryValidationRouter {
    fun route(result: ValidationResult): ValidationRoute {
        if (result.status == ValidationStatus.PASS) {
            return ValidationRoute(ValidationRouteAction.ADVANCE, result.agentId, result.taskId, "validação aprovada")
        }
        if (result.status == ValidationStatus.NEEDS_INPUT) {
            return ValidationRoute(ValidationRouteAction.ASK_USER, result.agentId, result.taskId, "informação do usuário necessária")
        }
        val owner = result.findingOwners.entries
            .sortedBy { it.key }
            .map { it.value }
            .firstOrNull()
        return when (owner) {
            FindingOwner.AGENT -> ValidationRoute(
                ValidationRouteAction.CORRECT_AGENT,
                result.agentId,
                result.taskId,
                "finding devolvido ao especialista responsável"
            )
            FindingOwner.USER -> ValidationRoute(ValidationRouteAction.ASK_USER, result.agentId, result.taskId, "finding exige informação do usuário")
            FindingOwner.POLICY -> ValidationRoute(ValidationRouteAction.REPORT_POLICY, result.agentId, result.taskId, "finding pertence à Policy; não há retry do agente")
            FindingOwner.INFRA, null -> ValidationRoute(ValidationRouteAction.RETRY_INFRA, result.agentId, result.taskId, "finding técnico exige retry de infraestrutura")
        }
    }
}
