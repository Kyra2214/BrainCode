package com.sandbox.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class OfflineBacklogTest {
    @Test
    fun `security regression is deterministic and catalog complete`() {
        val root = Files.createTempDirectory("security-corpus").toFile()
        val corpus = SecurityRegressionCorpus(root.resolve("regression.jsonl"))
        val scenarios = SecurityScenarioCatalog.baseline
        val first = corpus.runDeterministic(scenarios)
        val second = corpus.runDeterministic(scenarios)
        assertEquals(first, second)
        assertTrue(first.all { it.completed && it.blocked })
        assertEquals(scenarios.map { it.id }, first.map { it.scenarioId })
    }

    @Test
    fun `security corpus persists entries and digest`() {
        val root = Files.createTempDirectory("security-persist").toFile()
        val corpus = SecurityRegressionCorpus(root.resolve("regression.jsonl"))
        val report = SecurityTestLab().evaluate(SecurityScenarioCatalog.baseline, corpus.runDeterministic(SecurityScenarioCatalog.baseline))
        corpus.record(report)
        assertEquals(SecurityScenarioCatalog.baseline.size, corpus.entries().size)
        assertNotNull(corpus.digest())
        assertTrue(corpus.digest().length == 64)
    }

    @Test
    fun `toolchain transaction store round trips snapshot and cache`() {
        val root = Files.createTempDirectory("toolchain-state").toFile()
        val store = ToolchainTransactionStore(root)
        val profile = BuiltInToolchains.all.first { it.id == "python" }
        val status = ToolchainStatus(profile.id, ToolchainState.NOT_INSTALLED)
        store.saveBeforeInstall(profile, status)
        assertEquals(status.state, store.loadSnapshot(profile.id)?.state)
        store.cache(ToolchainStatus(profile.id, ToolchainState.INSTALLED, "Python 3.x"))
        assertEquals(ToolchainState.INSTALLED, store.cached(profile.id)?.state)
    }
}
