package com.brain.behavior

/** Resultado comum para todos os gates comportamentais. */
enum class GateStatus { READY, NEEDS_CLARIFICATION, BLOCKED, PASSED, FAILED }

data class GateIssue(
    val code: String,
    val message: String,
    val blocking: Boolean = true
) {
    init {
        require(code.isNotBlank()) { "código da issue não pode ser vazio" }
        require(message.isNotBlank()) { "mensagem da issue não pode ser vazia" }
    }
}

data class GateResult<T>(
    val status: GateStatus,
    val value: T? = null,
    val issues: List<GateIssue> = emptyList()
) {
    init {
        require(status == GateStatus.READY || status == GateStatus.PASSED || value != null || issues.isNotEmpty()) {
            "gate sem resultado, valor ou issue não é verificável"
        }
        require(status != GateStatus.BLOCKED || issues.any { it.blocking }) {
            "gate bloqueado precisa de uma issue bloqueante"
        }
    }

    val isSuccessful: Boolean get() = status == GateStatus.READY || status == GateStatus.PASSED
}

data class AcceptanceCriteria(
    val id: String,
    val description: String,
    val required: Boolean = true,
    val verification: String? = null
) {
    init {
        require(id.isNotBlank()) { "id do critério não pode ser vazio" }
        require(description.isNotBlank()) { "descrição do critério não pode ser vazia" }
    }

    /** Método verificável no formato `tipo:alvo` (ex.: `test:regression`). */
    fun verificationMethod(): VerificationMethod? {
        val raw = verification?.trim().orEmpty()
        val separator = raw.indexOf(':')
        if (separator <= 0 || separator == raw.lastIndex) return null
        val kind = raw.substring(0, separator).trim()
        val target = raw.substring(separator + 1).trim()
        return VerificationMethod(kind, target).takeIf { it.isValid }
    }

    fun structured(): AcceptanceCriterionContract = AcceptanceCriterionContract(
        id = id,
        description = description,
        required = required,
        verification = verificationMethod()
            ?: error("Acceptance Criteria '$id' precisa de verification no formato tipo:alvo")
    )
}

data class VerificationMethod(val kind: String, val target: String) {
    val isValid: Boolean get() = kind.isNotBlank() && target.isNotBlank()
}

data class AcceptanceCriterionContract(
    val id: String,
    val description: String,
    val required: Boolean,
    val verification: VerificationMethod
) {
    init {
        require(id.isNotBlank() && description.isNotBlank()) { "contrato de acceptance criterion incompleto" }
        require(verification.isValid) { "método de verificação inválido" }
    }
}

data class VerificationResult(
    val status: VerificationStatus,
    val checks: List<VerificationCheck>,
    val evidenceIds: List<String> = emptyList()
) {
    init { require(checks.isNotEmpty()) { "verificação precisa de ao menos um check" } }
    val passed: Boolean get() = status == VerificationStatus.PASSED && checks.all { it.passed }
}

enum class VerificationStatus { PASSED, FAILED, INCONCLUSIVE }

data class VerificationCheck(
    val criterionId: String,
    val passed: Boolean,
    val detail: String,
    val evidenceId: String? = null
) {
    init {
        require(criterionId.isNotBlank()) { "criterionId não pode ser vazio" }
        require(detail.isNotBlank()) { "detalhe do check não pode ser vazio" }
    }
}

enum class CritiqueStatus { PASS, FAIL, NEEDS_REVISION, BLOCKED }

data class CritiqueResult(
    val status: CritiqueStatus,
    val findings: List<CritiqueFinding>,
    val checkedCriteria: List<String> = emptyList()
) {
    init {
        require(status == CritiqueStatus.PASS || findings.isNotEmpty()) {
            "crítica não-PASS precisa registrar findings"
        }
    }
}

data class CritiqueFinding(
    val code: String,
    val message: String,
    val severity: FindingSeverity = FindingSeverity.MEDIUM,
    val criterionId: String? = null
) {
    init {
        require(code.isNotBlank()) { "código da finding não pode ser vazio" }
        require(message.isNotBlank()) { "mensagem da finding não pode ser vazia" }
    }
}

enum class FindingSeverity { LOW, MEDIUM, HIGH, BLOCKING }

data class ReadinessReport(
    val status: ReadinessStatus,
    val stages: List<ReadinessStage>,
    val blockers: List<String> = emptyList()
) {
    init {
        require(stages.map { it.name }.distinct().size == stages.size) { "estágios de readiness duplicados" }
        require(status != ReadinessStatus.BLOCKED || blockers.isNotEmpty()) { "readiness bloqueado precisa de blockers" }
    }
}

enum class ReadinessStatus { READY, BLOCKED }

data class ReadinessStage(
    val name: String,
    val passed: Boolean,
    val evidenceIds: List<String> = emptyList(),
    val detail: String = ""
) {
    init { require(name.isNotBlank()) { "nome do estágio não pode ser vazio" } }
}

data class ExecutionEvidence(
    val id: String,
    val kind: String,
    val summary: String,
    val source: String,
    val verified: Boolean = false
) {
    init {
        require(id.isNotBlank() && kind.isNotBlank() && summary.isNotBlank() && source.isNotBlank()) {
            "evidência precisa de id, tipo, resumo e fonte"
        }
    }
}

data class RevisionDecision(
    val action: RevisionAction,
    val reason: String,
    val targetCriteria: List<String> = emptyList(),
    val maxAttempts: Int = 1
) {
    init {
        require(reason.isNotBlank()) { "razão da revisão não pode ser vazia" }
        require(maxAttempts in 0..3) { "maxAttempts deve estar entre 0 e 3" }
    }
}

enum class RevisionAction { ACCEPT, REVISE, ABORT, ASK_CLARIFICATION }

interface BehaviorGate<I, O> {
    fun evaluate(input: I): GateResult<O>
}
