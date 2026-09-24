package com.sandbox.sandbox

import java.security.MessageDigest

data class SecurityAssessment(
    val scan: ProjectScanReport,
    val lab: SecurityTestReport,
    val findings: List<SecurityFinding>,
    val evidence: List<SecurityEvidence>,
    val readiness: SecurityReadiness
)

/** Combina sinais estáticos e probes controlados em um único gate de segurança. */
class SecurityAssessmentEngine(private val lab: SecurityTestLab = SecurityTestLab()) {
    fun evaluate(
        scan: ProjectScanReport,
        scenarios: List<SecurityScenario>,
        results: List<SecurityProbeResult>
    ): SecurityAssessment {
        val labReport = lab.evaluate(scenarios, results)
        val staticFindings = scan.findings.map { finding ->
            val evidence = evidenceFor(finding)
            SecurityFinding(
                scenarioId = "static:${finding.relativePath}:${finding.line}",
                severity = finding.rule.severity,
                title = finding.rule.description,
                detail = "${finding.relativePath}:${finding.line}",
                evidenceId = evidence.id
            )
        }
        val allFindings = labReport.findings + staticFindings
        val allEvidence = labReport.evidence + scan.findings.map(::evidenceFor)
        val blockers = allFindings
            .filter { it.severity == SecuritySeverity.HIGH || it.severity == SecuritySeverity.CRITICAL }
            .map { "${it.scenarioId}: ${it.title}" }
        val warnings = allFindings
            .filter { it.severity == SecuritySeverity.MEDIUM || it.severity == SecuritySeverity.LOW }
            .map { "${it.scenarioId}: ${it.title}" }
        return SecurityAssessment(
            scan = scan,
            lab = labReport,
            findings = allFindings,
            evidence = allEvidence,
            readiness = SecurityReadiness(blockers.isEmpty(), blockers, warnings)
        )
    }

    private fun evidenceFor(finding: ProjectScanFinding): SecurityEvidence {
        val excerpt = finding.evidence.take(512)
        val digest = MessageDigest.getInstance("SHA-256").digest(excerpt.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val id = "evidence-static-${finding.relativePath}-${finding.line}-$digest"
        return SecurityEvidence(id, "static:${finding.relativePath}:${finding.line}", digest, excerpt)
    }
}
