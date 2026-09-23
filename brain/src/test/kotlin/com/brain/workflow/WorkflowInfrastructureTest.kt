package com.brain.workflow

import java.nio.file.Files
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowInfrastructureTest {
    @Test
    fun `scheduler registra claim completa e persiste`() {
        val state = Files.createTempFile("workflow-scheduler", ".json").toFile()
        try {
            val document = WorkflowDocumentParser.parse(
                """
                    ---
                    name: nightly-review
                    version: 1.0.0
                    description: Nightly review
                    schedule: every 1 hour
                    ---
                    # Review
                """.trimIndent(), WorkflowSource.CUSTOM
            )
            val now = Instant.parse("2026-09-23T12:00:00Z")
            val scheduler = WorkflowScheduler(state)
            val registered = scheduler.register(document, "UTC", now)!!
            assertEquals(Instant.parse("2026-09-23T13:00:00Z"), registered.nextRun)
            val claimed = scheduler.claim("nightly-review", "worker", registered.nextRun)
            assertEquals("worker", claimed.claimedBy)
            val completed = scheduler.complete("nightly-review", "worker", registered.nextRun)
            assertTrue(completed.nextRun.isAfter(registered.nextRun))
            assertTrue(state.length() > 0)
        } finally { state.delete() }
    }

    @Test(expected = IllegalStateException::class)
    fun `marketplace rejeita manifesto sem assinatura confiavel`() {
        WorkflowMarketplaceRegistry().pin(
            WorkflowPackageManifest(
                id = "community-review",
                version = "1.0.0",
                sourceUrl = "https://example.com/workflow.zip",
                license = "MIT",
                contentHash = "a".repeat(64),
                signature = "invalid",
                signatureKeyId = "missing"
            )
        )
    }
}
