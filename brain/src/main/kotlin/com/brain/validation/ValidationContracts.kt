package com.brain.validation

import com.brain.secretary.Door

enum class ValidationStatus { PASS, FAIL, NEEDS_INPUT }

enum class ValidationLevel { LIGHT, CONTENT, AGENT, DOOR, PRODUCT }

enum class FindingOwner { AGENT, USER, POLICY, INFRA }

data class ValidationSubject(
    val capability: String,
    val agentId: String? = null,
    val door: Door? = null,
    val intent: String = "",
    val requirements: List<String> = emptyList(),
    val restrictions: Set<String> = emptySet(),
    val result: String = "",
    val evidence: List<String> = emptyList(),
    val missingRequirements: List<String> = emptyList(),
    val requiresInput: Boolean = false
)

data class ValidationCheck(
    val id: String,
    val description: String,
    val severity: FindingSeverity = FindingSeverity.BLOCKING,
    val owner: FindingOwner = FindingOwner.AGENT,
    val evaluate: (ValidationSubject) -> Boolean
)

enum class FindingSeverity { BLOCKING, HIGH, MEDIUM, LOW }

data class ValidationContract(
    val id: String,
    val capability: String,
    val level: ValidationLevel,
    val checks: List<ValidationCheck>,
    val requiredEvidence: List<String> = emptyList(),
    val maxAttempts: Int = 3
)

data class ValidationResult(
    val status: ValidationStatus,
    val contractId: String,
    val capability: String,
    val agentId: String? = null,
    val stage: String = "",
    val passedChecks: List<String> = emptyList(),
    val failedChecks: List<String> = emptyList(),
    val missingRequirements: List<String> = emptyList(),
    val evidenceIds: List<String> = emptyList(),
    val attempt: Int = 1,
    val previousResultId: String? = null,
    val findingOwners: Map<String, FindingOwner> = emptyMap()
) {
    val passed: Boolean get() = status == ValidationStatus.PASS
}

class ValidationEngine {
    fun validate(
        contract: ValidationContract,
        subject: ValidationSubject,
        stage: String = "",
        attempt: Int = 1,
        previousResultId: String? = null
    ): ValidationResult {
        val passed = contract.checks.filter { runCatching { it.evaluate(subject) }.getOrDefault(false) }
        val failed = contract.checks.filterNot { it in passed }
        val missingEvidence = contract.requiredEvidence.filterNot { required -> subject.evidence.any { it.startsWith(required) } }
        val failedIds = (failed.map { it.id } + missingEvidence.map { "evidence:$it" }).distinct()
        val status = when {
            subject.requiresInput && contract.level == ValidationLevel.LIGHT -> ValidationStatus.NEEDS_INPUT
            failedIds.isEmpty() -> ValidationStatus.PASS
            else -> ValidationStatus.FAIL
        }
        val owners = failed.associate { it.id to it.owner }.toMutableMap()
        missingEvidence.forEach { owners["evidence:$it"] = FindingOwner.AGENT }
        return ValidationResult(
            status = status,
            contractId = contract.id,
            capability = contract.capability,
            agentId = subject.agentId,
            stage = stage,
            passedChecks = passed.map { it.id },
            failedChecks = failedIds,
            missingRequirements = subject.missingRequirements,
            evidenceIds = subject.evidence,
            attempt = attempt,
            previousResultId = previousResultId,
            findingOwners = owners
        )
    }

    fun selfAgent(subject: ValidationSubject, stage: String = "agent", attempt: Int = 1): ValidationResult =
        validate(
            ValidationContract(
                id = "agent.self.generic",
                capability = subject.capability,
                level = ValidationLevel.AGENT,
                checks = listOf(
                    ValidationCheck("result-or-evidence", "especialista deve produzir resultado ou evidência") { it.result.isNotBlank() || it.evidence.isNotEmpty() }
                )
            ),
            subject,
            stage,
            attempt
        )

    fun productPhase(
        phase: String,
        subject: ValidationSubject,
        stage: String = "door.create",
        attempt: Int = 1
    ): ValidationResult =
        validate(
            ValidationContract(
                id = "door.create.phase." + phase.lowercase().replace(" ", "-"),
                capability = subject.capability,
                level = ValidationLevel.PRODUCT,
                checks = listOf(
                    ValidationCheck("phase.result-or-evidence", "fase produz resultado ou evidência") {
                        it.result.isNotBlank() || it.evidence.isNotEmpty()
                    }
                )
            ),
            subject,
            stage,
            attempt
        )

    fun promptContent(subject: ValidationSubject, stage: String = "door.prompt", attempt: Int = 1): ValidationResult {
        val checks = subject.requirements.mapIndexed { index, requirement ->
            ValidationCheck(
                id = "prompt.requirement.$index",
                description = "requisito do prompt presente",
                severity = FindingSeverity.HIGH,
                owner = FindingOwner.AGENT
            ) { com.brain.behavior.RequirementMatcher.isPresent(requirement, it.result) }
        }
        return validate(
            ValidationContract(
                id = "door.prompt.content",
                capability = subject.capability,
                level = ValidationLevel.CONTENT,
                checks = checks + ValidationCheck(
                    "prompt.non-empty",
                    "prompt final não vazio"
                ) { it.result.isNotBlank() }
            ),
            subject,
            stage,
            attempt
        )
    }

    fun lightChat(subject: ValidationSubject, stage: String = "door.chat", attempt: Int = 1): ValidationResult =
        validate(
            ValidationContract(
                id = "door.chat.light",
                capability = "chat.respond",
                level = ValidationLevel.LIGHT,
                checks = listOf(
                    ValidationCheck("response.non-empty", "resposta não vazia") { it.result.isNotBlank() },
                    ValidationCheck("no-workspace", "chat não executa workspace/sandbox") {
                        it.evidence.none { evidence -> evidence.startsWith("workspace.") || evidence.startsWith("sandbox.") }
                    },
                    ValidationCheck("no-policy-denial-success", "negação de Policy não conta como sucesso") {
                        it.evidence.none { evidence -> evidence == "NEGADO_PELA_POLICY" }
                    }
                ),
                requiredEvidence = listOf("chat:")
            ),
            subject,
            stage,
            attempt
        )
}
