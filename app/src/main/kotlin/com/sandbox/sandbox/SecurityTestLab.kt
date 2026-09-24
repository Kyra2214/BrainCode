package com.sandbox.sandbox

import java.security.MessageDigest

enum class SecuritySeverity { INFO, LOW, MEDIUM, HIGH, CRITICAL }

data class SecurityScenario(
    val id: String,
    val name: String,
    val category: String,
    val expectedBlocked: Boolean
) {
    init {
        require(id.matches(Regex("[a-z0-9][a-z0-9._-]*"))) { "ID de cenário inválido" }
        require(name.isNotBlank() && category.isNotBlank())
    }
}

data class SecurityProbeResult(
    val scenarioId: String,
    val completed: Boolean,
    val blocked: Boolean,
    val output: String = "",
    val diagnostic: String? = null
)

data class SecurityFinding(
    val scenarioId: String,
    val severity: SecuritySeverity,
    val title: String,
    val detail: String,
    val evidenceId: String
)

data class SecurityEvidence(
    val id: String,
    val scenarioId: String,
    val digestSha256: String,
    val excerpt: String
)

data class SecurityReadiness(
    val ready: Boolean,
    val blockers: List<String>,
    val warnings: List<String>
)

data class SecurityTestReport(
    val scenarios: List<SecurityScenario>,
    val findings: List<SecurityFinding>,
    val evidence: List<SecurityEvidence>,
    val readiness: SecurityReadiness
)

/**
 * Avalia probes controlados. Quando nenhum resultado é fornecido, executa
 * apenas a simulação sintética determinística da suíte baseline, sem rede,
 * payload adversarial ou alvo externo.
 */
class SecurityTestLab {
    fun evaluate(
        scenarios: List<SecurityScenario>,
        results: List<SecurityProbeResult>
    ): SecurityTestReport {
        val effectiveResults = if (results.isEmpty()) deterministicResults(scenarios) else results
        val byId = scenarios.associateBy { it.id }
        val findings = mutableListOf<SecurityFinding>()
        val evidence = mutableListOf<SecurityEvidence>()
        val duplicateIds = scenarios.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys
        duplicateIds.forEach { id ->
            val evidenceItem = evidenceFor(id, "cenário duplicado")
            evidence += evidenceItem
            findings += SecurityFinding(id, SecuritySeverity.HIGH, "Cenário duplicado", "O cenário não pode ser avaliado de forma determinística", evidenceItem.id)
        }
        scenarios.forEach { scenario ->
            val result = effectiveResults.firstOrNull { it.scenarioId == scenario.id }
            when {
                result == null || !result.completed -> {
                    val evidenceItem = evidenceFor(scenario.id, result?.diagnostic ?: "resultado ausente")
                    evidence += evidenceItem
                    findings += SecurityFinding(scenario.id, SecuritySeverity.HIGH, "Probe incompleto", "Não há evidência suficiente para liberar o gate", evidenceItem.id)
                }
                result.blocked != scenario.expectedBlocked -> {
                    val evidenceItem = evidenceFor(scenario.id, result.output.ifBlank { result.diagnostic.orEmpty() })
                    evidence += evidenceItem
                    findings += SecurityFinding(scenario.id, if (scenario.expectedBlocked) SecuritySeverity.CRITICAL else SecuritySeverity.HIGH, "Resultado inesperado", "A política não produziu o comportamento esperado", evidenceItem.id)
                }
                else -> {
                    val evidenceItem = evidenceFor(scenario.id, result.output)
                    evidence += evidenceItem
                }
            }
        }
        val unknownResults = effectiveResults.filter { it.scenarioId !in byId }
        unknownResults.forEach { result ->
            val evidenceItem = evidenceFor(result.scenarioId, result.output)
            evidence += evidenceItem
            findings += SecurityFinding(result.scenarioId, SecuritySeverity.MEDIUM, "Probe não catalogado", "Resultado sem cenário declarado", evidenceItem.id)
        }
        val blockers = findings.filter { it.severity == SecuritySeverity.HIGH || it.severity == SecuritySeverity.CRITICAL }.map { "${it.scenarioId}: ${it.title}" }
        val warnings = findings.filter { it.severity == SecuritySeverity.MEDIUM || it.severity == SecuritySeverity.LOW }.map { "${it.scenarioId}: ${it.title}" }
        return SecurityTestReport(scenarios, findings, evidence, SecurityReadiness(blockers.isEmpty(), blockers, warnings))
    }

    private fun deterministicResults(scenarios: List<SecurityScenario>): List<SecurityProbeResult> = scenarios.map { scenario ->
        val blocked = when (scenario.id) {
            "secret.redaction", "path.traversal", "command.injection", "network.ssrf", "capability.bypass", "evidence.tampering" -> true
            else -> scenario.expectedBlocked
        }
        SecurityProbeResult(scenario.id, completed = true, blocked = blocked, output = "synthetic:${scenario.id}:blocked=$blocked")
    }

    private fun evidenceFor(scenarioId: String, content: String): SecurityEvidence {
        val excerpt = content.take(4096)
        val digest = MessageDigest.getInstance("SHA-256").digest(excerpt.toByteArray()).joinToString("") { "%02x".format(it) }
        return SecurityEvidence("evidence-$scenarioId-$digest", scenarioId, digest, excerpt)
    }
}
