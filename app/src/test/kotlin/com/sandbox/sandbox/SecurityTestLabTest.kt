package com.sandbox.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecurityTestLabTest {
    private val blocked = SecurityScenario("network-egress", "Egress sem regra", "network", expectedBlocked = true)
    private val allowed = SecurityScenario("workspace-read", "Leitura autorizada", "filesystem", expectedBlocked = false)

    @Test
    fun `gate fica pronto quando probes correspondem ao esperado`() {
        val report = SecurityTestLab().evaluate(
            listOf(blocked, allowed),
            listOf(
                SecurityProbeResult(blocked.id, completed = true, blocked = true, output = "denied"),
                SecurityProbeResult(allowed.id, completed = true, blocked = false, output = "ok")
            )
        )

        assertTrue(report.readiness.ready)
        assertTrue(report.findings.isEmpty())
        assertEquals(2, report.evidence.size)
    }

    @Test
    fun `resultado inesperado cria blocker critical para bloqueio esperado`() {
        val report = SecurityTestLab().evaluate(
            listOf(blocked),
            listOf(SecurityProbeResult(blocked.id, completed = true, blocked = false, output = "escaped"))
        )

        assertFalse(report.readiness.ready)
        assertEquals(SecuritySeverity.CRITICAL, report.findings.single().severity)
        assertTrue(report.findings.single().evidenceId.startsWith("evidence-network-egress-"))
    }

    @Test
    fun `probe ausente bloqueia readiness`() {
        val report = SecurityTestLab().evaluate(
            listOf(blocked),
            listOf(SecurityProbeResult(blocked.id, completed = false, blocked = false, diagnostic = "probe não concluído"))
        )
        assertFalse(report.readiness.ready)
        assertTrue(report.readiness.blockers.single().contains("Probe incompleto"))
    }

    @Test
    fun `resultado desconhecido vira warning sem liberar silencio`() {
        val report = SecurityTestLab().evaluate(
            emptyList(),
            listOf(SecurityProbeResult("unlisted", completed = true, blocked = false, output = "observed"))
        )
        assertTrue(report.readiness.ready)
        assertEquals(SecuritySeverity.MEDIUM, report.findings.single().severity)
        assertEquals(1, report.readiness.warnings.size)
    }

    @Test
    fun `conteudo da evidencia e truncado`() {
        val output = "x".repeat(5000)
        val report = SecurityTestLab().evaluate(
            listOf(allowed), listOf(SecurityProbeResult(allowed.id, true, false, output))
        )
        assertEquals(4096, report.evidence.single().excerpt.length)
        assertEquals(64, report.evidence.single().digestSha256.length)
    }
}
