package com.brain.validation

/** Contratos mínimos declarados para especialistas bounded.
 * Um contrato existir não torna o agente executável: Policy/Registry/executor
 * continuam exigindo disponibilidade explícita.
 */
object BuiltInValidationContracts {
    private val agentIds = listOf(
        "agent.research",
        "agent.code",
        "agent.requirements",
        "agent.architecture",
        "agent.roadmap",
        "agent.ui",
        "agent.backend",
        "agent.database",
        "agent.security",
        "agent.test",
        "agent.review",
        "agent.integration",
        "agent.release",
        "agent.documentation"
    )

    fun all(): Map<String, ValidationContract> = agentIds.associateWith { id ->
        ValidationContract(
            id = "self-e2e:$id",
            capability = id,
            level = ValidationLevel.AGENT,
            checks = listOf(
                ValidationCheck("result-or-evidence", "especialista produz resultado ou evidência") { it.result.isNotBlank() || it.evidence.isNotEmpty() }
            )
        )
    }

    fun forAgent(agentId: String): ValidationContract? = all()[agentId]
}
