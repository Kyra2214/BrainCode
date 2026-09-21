package com.brain.validation

/**
 * Registro único que liga capability executável ao contrato Self-E2E e ao
 * responsável lógico. Não cria agentes nem providers; apenas fecha o vínculo
 * de validação para capacidades que já existem no runtime.
 */
object ValidationContractRegistry {
    private val capabilityOwners = mapOf(
        "research.web" to "agent.research",
        "research.evidence" to "agent.research",
        "workspace.generate" to "agent.code",
        "sandbox.build" to "agent.code",
        "sandbox.test" to "agent.test",
        "sandbox.diagnose" to "agent.review",
        "prompt.library.generate" to "local.prompt.creator"
    )

    private val localContracts = mapOf(
        "local.prompt.creator" to ValidationContract(
            id = "self-e2e:local.prompt.creator",
            capability = "prompt.library.generate",
            level = ValidationLevel.AGENT,
            checks = listOf(
                ValidationCheck("result-or-evidence", "criador de prompt produz resultado ou evidência") {
                    it.result.isNotBlank() || it.evidence.isNotEmpty()
                },
                ValidationCheck("prompt.requirements-preserved", "requisitos explícitos permanecem no resultado", FindingSeverity.HIGH) {
                    it.requirements.all { requirement -> com.brain.behavior.RequirementMatcher.isPresent(requirement, it.result) }
                }
            )
        )
    )

    fun ownerForCapability(capability: String): String? =
        capabilityOwners.entries.firstOrNull { (prefix, _) ->
            capability == prefix || capability.startsWith("$prefix.")
        }?.value

    fun contractForCapability(capability: String): ValidationContract? {
        val owner = ownerForCapability(capability) ?: return null
        return localContracts[owner] ?: BuiltInValidationContracts.forAgent(owner)?.copy(capability = capability)
    }

    fun contractForOwner(ownerId: String, capability: String): ValidationContract? =
        localContracts[ownerId] ?: BuiltInValidationContracts.forAgent(ownerId)?.copy(capability = capability)
}
