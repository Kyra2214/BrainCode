package com.brain.memory

import com.brain.skill.SkillManifest

/** Evidência mínima de execução bem-sucedida usada para detectar repetição. */
data class SuccessfulProcedure(
    val executionId: String,
    val objective: String,
    val capability: String,
    val success: Boolean,
    val evidence: List<String>,
    val provenance: List<String> = emptyList()
) {
    init {
        require(executionId.isNotBlank()) { "executionId é obrigatório" }
        require(objective.isNotBlank()) { "objective é obrigatório" }
        require(capability.isNotBlank()) { "capability é obrigatória" }
        require(evidence.none { it.isBlank() }) { "evidência não pode ser vazia" }
        require(provenance.none { it.isBlank() }) { "proveniência não pode ser vazia" }
    }
}

data class AutoSkillProposal(
    val procedureKey: String,
    val manifest: SkillManifest,
    val evidenceIds: List<String>,
    val provenance: List<String>
)

/**
 * Detector conservador de Auto-Skills. Ele nunca chama SkillRegistry e nunca
 * considera falha ou execução sem evidência como base para promoção. A saída
 * é uma proposta que deve passar por validação explícita posteriormente.
 */
class AutoSkillDetector(private val minimumSuccessfulExecutions: Int = 2) {
    init { require(minimumSuccessfulExecutions > 0) { "mínimo de execuções deve ser positivo" } }

    fun detect(
        executions: List<SuccessfulProcedure>,
        manifestFactory: (procedureKey: String, capability: String) -> SkillManifest
    ): List<AutoSkillProposal> {
        return executions
            .filter { it.success && it.evidence.isNotEmpty() }
            .groupBy { key(it.objective, it.capability) }
            .filterValues { it.size >= minimumSuccessfulExecutions }
            .map { (procedureKey, evidence) ->
                val capability = evidence.first().capability
                AutoSkillProposal(
                    procedureKey = procedureKey,
                    manifest = manifestFactory(procedureKey, capability),
                    evidenceIds = evidence.map { it.executionId }.distinct(),
                    provenance = evidence.flatMap { it.provenance }.distinct()
                )
            }
            .sortedBy { it.procedureKey }
    }

    private fun key(objective: String, capability: String): String =
        "${objective.trim().lowercase()}::$capability"
}
