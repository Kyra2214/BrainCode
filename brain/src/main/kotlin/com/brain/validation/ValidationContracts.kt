package com.brain.validation

import com.brain.secretary.Door
import java.util.UUID

enum class ValidationStatus { PASS, FAIL, NEEDS_INPUT }
enum class ValidationLevel { LIGHT, CONTENT, AGENT, DOOR, PRODUCT }
enum class FindingOwner { AGENT, USER, POLICY, INFRA }
enum class FindingSeverity { BLOCKING, HIGH, MEDIUM, LOW }

data class ValidationSubject(
    val capability: String,
    val taskId: String? = null,
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

data class ValidationContract(
    val id: String,
    val capability: String,
    val level: ValidationLevel,
    val checks: List<ValidationCheck>,
    val requiredEvidence: List<String> = emptyList(),
    val maxAttempts: Int = 3
) {
    init {
        require(id.isNotBlank() && capability.isNotBlank()) { "contrato de validação incompleto" }
        require(checks.isNotEmpty()) { "contrato de validação precisa de checks" }
        require(maxAttempts in 1..3) { "maxAttempts deve estar entre 1 e 3" }
    }
}

data class ValidationResult(
    val status: ValidationStatus,
    val contractId: String,
    val taskId: String? = null,
    val capability: String,
    val agentId: String? = null,
    val stage: String = "",
    val passedChecks: List<String> = emptyList(),
    val failedChecks: List<String> = emptyList(),
    val missingRequirements: List<String> = emptyList(),
    val evidenceIds: List<String> = emptyList(),
    val attempt: Int = 1,
    val previousResultId: String? = null,
    val findingOwners: Map<String, FindingOwner> = emptyMap(),
    val resultId: String = UUID.randomUUID().toString()
) {
    val passed: Boolean get() = status == ValidationStatus.PASS
    val correctionRequired: Boolean get() = status == ValidationStatus.FAIL
}

class ValidationEngine {
    fun validate(
        contract: ValidationContract,
        subject: ValidationSubject,
        stage: String = "",
        attempt: Int = 1,
        previousResultId: String? = null
    ): ValidationResult {
        require(attempt >= 1) { "attempt deve ser >= 1" }
        if (attempt > contract.maxAttempts) {
            return ValidationResult(
                status = ValidationStatus.FAIL,
                contractId = contract.id,
                taskId = subject.taskId,
                capability = contract.capability,
                agentId = subject.agentId,
                stage = stage,
                failedChecks = listOf("attempt-limit"),
                evidenceIds = subject.evidence,
                attempt = attempt,
                previousResultId = previousResultId,
                findingOwners = mapOf("attempt-limit" to FindingOwner.INFRA)
            )
        }
        val passed = contract.checks.filter { runCatching { it.evaluate(subject) }.getOrDefault(false) }
        val failed = contract.checks.filterNot { it in passed }
        val missingEvidence = contract.requiredEvidence.filterNot { required ->
            subject.evidence.any { it.startsWith(required) }
        }
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
            taskId = subject.taskId,
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

    fun selfAgent(subject: ValidationSubject, stage: String = "agent", attempt: Int = 1, previousResultId: String? = null): ValidationResult {
        val contract = ValidationContractRegistry.contractForCapability(subject.capability)
        val owner = ValidationContractRegistry.ownerForCapability(subject.capability)
        if (contract == null || owner == null) {
            return ValidationResult(
                status = ValidationStatus.FAIL,
                contractId = "self-e2e:unregistered:" + subject.capability,
                taskId = subject.taskId,
                capability = subject.capability,
                agentId = subject.agentId,
                stage = stage,
                failedChecks = listOf("contract.missing", "agent.unavailable"),
                evidenceIds = subject.evidence,
                attempt = attempt,
                previousResultId = previousResultId,
                findingOwners = mapOf(
                    "contract.missing" to FindingOwner.INFRA,
                    "agent.unavailable" to FindingOwner.AGENT
                )
            )
        }
        return validate(contract, subject.copy(agentId = subject.agentId ?: owner), stage, attempt, previousResultId)
    }

    fun productPhase(phase: String, subject: ValidationSubject, stage: String = "door.create", attempt: Int = 1, previousResultId: String? = null): ValidationResult {
        val normalized = phase.uppercase()
        val checks = when (normalized) {
            "DISCUSSION", "REQUIREMENTS" -> listOf(ValidationCheck("phase.requirements", "fase possui resultado/requisitos", FindingSeverity.HIGH) { it.result.isNotBlank() || it.requirements.isNotEmpty() })
            "ARCHITECTURE" -> listOf(ValidationCheck("phase.architecture", "arquitetura possui resultado", FindingSeverity.HIGH) { it.result.isNotBlank() })
            "PLAN" -> listOf(ValidationCheck("phase.plan", "plano possui resultado e evidência", FindingSeverity.HIGH) { it.result.isNotBlank() && it.evidence.isNotEmpty() })
            "APPROVED" -> listOf(ValidationCheck("phase.approval", "aprovação persistente/evidência presente") { it.evidence.any { evidence -> evidence.startsWith("approval:") } })
            "EXECUTION", "INTEGRATION" -> listOf(ValidationCheck("phase.execution", "execução produz resultado/evidência", FindingSeverity.BLOCKING) { it.result.isNotBlank() || it.evidence.any { evidence -> evidence.startsWith("execution:") } })
            "REVIEW" -> listOf(ValidationCheck("phase.review", "revisão produz resultado", FindingSeverity.HIGH) { it.result.isNotBlank() })
            "TESTS" -> listOf(ValidationCheck("phase.tests", "testes produzem evidência", FindingSeverity.BLOCKING) { it.evidence.any { evidence -> evidence.startsWith("test:") || evidence.startsWith("verification:") } })
            "DELIVERY" -> listOf(ValidationCheck("phase.delivery", "entrega possui evidência verificável", FindingSeverity.BLOCKING) { it.evidence.any { evidence -> evidence.startsWith("delivery:") || evidence.startsWith("zip:") } })
            else -> listOf(ValidationCheck("phase.result-or-evidence", "fase produz resultado ou evidência") { it.result.isNotBlank() || it.evidence.isNotEmpty() })
        }
        return validate(ValidationContract("door.create.phase." + normalized.lowercase(), subject.capability, ValidationLevel.PRODUCT, checks), subject, stage, attempt, previousResultId)
    }

    fun promptContent(subject: ValidationSubject, stage: String = "door.prompt", attempt: Int = 1, previousResultId: String? = null): ValidationResult {
        val checks = subject.requirements.filterNot(::isStructuredSlotRequirement).mapIndexed { index, requirement ->
            ValidationCheck("prompt.requirement." + index, "requisito explícito do prompt presente", FindingSeverity.HIGH, FindingOwner.AGENT) {
                com.brain.behavior.RequirementMatcher.isPresent(requirement, it.result)
            }
        } + promptSlotChecks(subject.requirements)
        return validate(
            ValidationContract(
                "door.prompt.content",
                subject.capability,
                ValidationLevel.CONTENT,
                checks.distinctBy { it.id } + ValidationCheck("prompt.non-empty", "prompt final não vazio") { it.result.isNotBlank() }
            ),
            subject, stage, attempt, previousResultId
        )
    }

    private fun promptSlotChecks(requirements: List<String>): List<ValidationCheck> {
        val aliases = mapOf(
            "sujeito" to "subject", "subject" to "subject",
            "ação" to "action", "acao" to "action", "action" to "action",
            "ambiente" to "environment", "environment" to "environment",
            "elementos" to "elements", "elements" to "elements",
            "estilo" to "style", "style" to "style",
            "iluminação" to "lighting", "iluminacao" to "lighting", "lighting" to "lighting",
            "composição" to "composition", "composicao" to "composition", "composition" to "composition",
            "formato" to "format", "format" to "format",
            "restrições" to "restrictions", "restricoes" to "restrictions", "restrictions" to "restrictions"
        )
        return requirements.mapNotNull { requirement ->
            val match = Regex("^\\s*([\\p{L}]+)\\s*[:=]\\s*(.+?)\\s*$").find(requirement) ?: return@mapNotNull null
            val slot = aliases[match.groupValues[1].lowercase()] ?: return@mapNotNull null
            val value = match.groupValues[2].trim()
            if (value.isBlank()) return@mapNotNull null
            ValidationCheck("prompt.slot.$slot", "slot $slot presente no prompt", FindingSeverity.HIGH, FindingOwner.AGENT) {
                com.brain.behavior.RequirementMatcher.isPresent(value, it.result)
            }
        }
    }

    private fun isStructuredSlotRequirement(requirement: String): Boolean {
        val key = Regex("^\\s*([\\p{L}]+)\\s*[:=]").find(requirement)?.groupValues?.getOrNull(1)?.lowercase()
            ?: return false
        return key in setOf(
            "sujeito", "subject", "ação", "acao", "action", "ambiente", "environment",
            "elementos", "elements", "estilo", "style", "iluminação", "iluminacao", "lighting",
            "composição", "composicao", "composition", "formato", "format", "restrições", "restricoes", "restrictions"
        )
    }

    fun lightChat(subject: ValidationSubject, stage: String = "door.chat", attempt: Int = 1, previousResultId: String? = null): ValidationResult =
        validate(
            ValidationContract(
                "door.chat.light", "chat.respond", ValidationLevel.LIGHT,
                listOf(
                    ValidationCheck("response.non-empty", "resposta não vazia") { it.result.isNotBlank() },
                    ValidationCheck("no-workspace", "chat não executa workspace/sandbox") { it.evidence.none { evidence -> evidence.startsWith("workspace.") || evidence.startsWith("sandbox.") } },
                    ValidationCheck("no-policy-denial-success", "negação de Policy não conta como sucesso") { it.evidence.none { evidence -> evidence == "NEGADO_PELA_POLICY" } },
                    ValidationCheck("restriction.no-web", "NO_WEB impede evidência de rede") {
                        "NO_WEB" !in it.restrictions || it.evidence.none { evidence -> evidence.startsWith("web:") }
                    }
                ),
                requiredEvidence = listOf("chat:", "chat:secretary:accept", "chat:request:")
            ),
            subject, stage, attempt, previousResultId
        )
}
