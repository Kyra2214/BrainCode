package com.sandbox.sandbox

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecurityAssessmentTest {
    @Test
    fun `combina findings estaticos e probes no mesmo gate`() {
        val root = Files.createTempDirectory("assessment").toFile()
        root.resolve("config.env").writeText("API_KEY=super-secret-value")
        val scan = SecurityProjectScanner().scan(root)
        val scenario = SecurityScenario("egress", "Egress", "network", expectedBlocked = true)

        val assessment = SecurityAssessmentEngine().evaluate(
            scan,
            listOf(scenario),
            listOf(SecurityProbeResult("egress", completed = true, blocked = true, output = "denied"))
        )

        assertFalse(assessment.readiness.ready)
        assertTrue(assessment.findings.any { it.scenarioId.startsWith("static:") })
        assertTrue(assessment.findings.any { it.scenarioId == "egress" }.not())
        assertTrue(assessment.evidence.any { it.scenarioId.startsWith("static:") })
    }

    @Test
    fun `assessment sem risco e probes corretos libera gate`() {
        val scan = ProjectScanReport("/tmp/project", filesScanned = 1, findings = emptyList(), skippedFiles = 0)
        val assessment = SecurityAssessmentEngine().evaluate(
            scan,
            emptyList(),
            emptyList()
        )

        assertTrue(assessment.readiness.ready)
        assertEquals(0, assessment.findings.size)
        assertEquals(0, assessment.evidence.size)
    }
}
