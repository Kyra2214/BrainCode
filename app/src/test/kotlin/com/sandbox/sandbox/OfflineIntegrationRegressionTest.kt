package com.sandbox.sandbox

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Contratos determinísticos das conexões fechadas no backlog offline. */
class OfflineIntegrationRegressionTest {
    @Test
    fun `security corpus records observed probe result`() {
        val dir = Files.createTempDirectory("braincode-security").toFile()
        val corpus = SecurityRegressionCorpus(dir.resolve("corpus.jsonl"))
        val scenario = SecurityScenarioCatalog.baseline.first()
        val result = SecurityProbeResult(scenario.id, completed = true, blocked = scenario.expectedBlocked, output = "test")
        val report = SecurityTestLab().evaluate(listOf(scenario), listOf(result))
        corpus.record(report, listOf(result))
        val entry = corpus.entries().single()
        assertEquals(result.blocked, entry.observedBlocked)
        assertEquals(scenario.expectedBlocked, entry.expectedBlocked)
        assertTrue(corpus.digest().isNotBlank())
    }
}
