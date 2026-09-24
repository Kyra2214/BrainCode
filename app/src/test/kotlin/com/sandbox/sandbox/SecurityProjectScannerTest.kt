package com.sandbox.sandbox

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecurityProjectScannerTest {
    @Test
    fun `encontra riscos e redige credencial`() {
        val root = Files.createTempDirectory("security-scan").toFile()
        root.resolve("config.env").writeText("API_KEY=super-secret-value\nverify=false\n")
        root.resolve("id.pem").writeText("-----BEGIN RSA PRIVATE KEY-----\nmaterial\n")

        val report = SecurityProjectScanner().scan(root)

        assertEquals(3, report.findings.size)
        assertEquals(3, report.blockers.size)
        assertTrue(report.findings.first { it.rule == ScanRule.CREDENTIAL_ASSIGNMENT }.evidence.contains("<redacted>"))
        assertFalse(report.findings.any { it.evidence.contains("super-secret-value") })
    }

    @Test
    fun `ignora diretorios e arquivos acima do limite`() {
        val root = Files.createTempDirectory("security-scan").toFile()
        root.resolve("build").mkdirs()
        root.resolve("build/generated.txt").writeText("TOKEN='hidden-build-secret'")
        root.resolve("large.txt").writeText("x".repeat(200))

        val report = SecurityProjectScanner(maxFileBytes = 100).scan(root)

        assertEquals(0, report.filesScanned)
        assertEquals(2, report.skippedFiles)
        assertTrue(report.findings.isEmpty())
    }

    @Test
    fun `mantem caminho relativo e linha`() {
        val root = Files.createTempDirectory("security-scan").toFile()
        root.resolve("src").mkdirs()
        root.resolve("src/app.py").writeText("ok\npassword = '12345678'\n")

        val finding = SecurityProjectScanner().scan(root).findings.single()

        assertEquals("src/app.py", finding.relativePath)
        assertEquals(2, finding.line)
    }
}
