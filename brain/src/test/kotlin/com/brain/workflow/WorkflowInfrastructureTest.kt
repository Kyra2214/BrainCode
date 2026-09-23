package com.brain.workflow

import java.nio.file.Files
import java.time.Instant
import java.nio.charset.StandardCharsets
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun `scheduler rejeita owner concorrente e aceita claim apos lease expirar`() {
        val state = Files.createTempFile("workflow-scheduler-lease", ".json").toFile()
        try {
            val document = WorkflowDocumentParser.parse(
                """
                    ---
                    name: lease-review
                    version: 1.0.0
                    description: Lease review
                    schedule: every 1 hour
                    ---
                    # Review
                """.trimIndent(), WorkflowSource.CUSTOM
            )
            val now = Instant.parse("2026-09-23T12:00:00Z")
            val scheduler = WorkflowScheduler(state)
            scheduler.register(document, "UTC", now)
            scheduler.claim("lease-review", "owner-a", now.plusSeconds(3600), leaseSeconds = 60)
            val concurrent = runCatching { scheduler.claim("lease-review", "owner-b", now.plusSeconds(3600)) }.exceptionOrNull()
            assertTrue(concurrent is IllegalStateException)
            val expired = scheduler.claim("lease-review", "owner-b", now.plusSeconds(3661))
            assertEquals("owner-b", expired.claimedBy)
            val wrongOwner = runCatching { scheduler.complete("lease-review", "owner-a", now.plusSeconds(3661)) }.exceptionOrNull()
            assertTrue(wrongOwner is IllegalStateException)
            assertFalse(scheduler.due(now.plusSeconds(3661)).any { it.id == "lease-review" })
        } finally { state.delete() }
    }

    @Test
    fun `scheduler ignora persistencia corrompida sem vazar estado parcial`() {
        val state = File.createTempFile("workflow-scheduler-corrupt", ".json")
        try {
            state.writeText("[{not-json]")
            val scheduler = WorkflowScheduler(state)
            assertTrue(scheduler.due(Instant.parse("2026-09-23T12:00:00Z")).isEmpty())
        } finally { state.delete() }
    }

    @Test(expected = SecurityException::class)
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

    @Test
    fun `marketplace aceita manifesto com assinatura Ed25519 valida`() {
        val keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val manifest = signedManifest(keyPair, keyId = "test-key")
        val registry = WorkflowMarketplaceRegistry(mapOf("test-key" to keyPair.public.encoded))

        assertEquals(manifest, registry.pin(manifest))
        assertEquals(manifest, registry.resolve("community-review", "1.0.0"))
    }

    @Test(expected = SecurityException::class)
    fun `marketplace rejeita assinatura adulterada`() {
        val keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val original = signedManifest(keyPair, keyId = "test-key")
        WorkflowMarketplaceRegistry(mapOf("test-key" to keyPair.public.encoded)).pin(
            original.copy(contentHash = "b".repeat(64))
        )
    }

    @Test(expected = SecurityException::class)
    fun `marketplace rejeita keyId desconhecido`() {
        val keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val manifest = signedManifest(keyPair, keyId = "missing")
        WorkflowMarketplaceRegistry(mapOf("other" to keyPair.public.encoded)).pin(manifest)
    }

    @Test(expected = SecurityException::class)
    fun `marketplace rejeita algoritmo diferente`() {
        val keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val manifest = signedManifest(keyPair, keyId = "test-key", algorithm = "RSA")
        WorkflowMarketplaceRegistry(mapOf("test-key" to keyPair.public.encoded)).pin(manifest)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `marketplace rejeita URL sem HTTPS`() {
        val keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val manifest = signedManifest(keyPair, keyId = "test-key", sourceUrl = "http://example.com/workflow.zip")
        WorkflowMarketplaceRegistry(mapOf("test-key" to keyPair.public.encoded)).pin(manifest)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `marketplace rejeita hash malformado`() {
        val keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val manifest = signedManifest(keyPair, keyId = "test-key", contentHash = "not-a-hash")
        WorkflowMarketplaceRegistry(mapOf("test-key" to keyPair.public.encoded)).pin(manifest)
    }

    private fun signedManifest(
        keyPair: KeyPair,
        keyId: String,
        sourceUrl: String = "https://example.com/workflow.zip",
        contentHash: String = "a".repeat(64),
        algorithm: String = "Ed25519"
    ): WorkflowPackageManifest {
        val unsigned = WorkflowPackageManifest(
            id = "community-review",
            version = "1.0.0",
            sourceUrl = sourceUrl,
            license = "MIT",
            contentHash = contentHash,
            signature = "",
            signatureKeyId = keyId,
            signatureAlgorithm = algorithm
        )
        val signature = Signature.getInstance("Ed25519").apply {
            initSign(keyPair.private)
            update(payload(unsigned).toByteArray(StandardCharsets.UTF_8))
        }.sign()
        return unsigned.copy(signature = Base64.getEncoder().encodeToString(signature))
    }

    private fun payload(manifest: WorkflowPackageManifest): String = listOf(
        manifest.id, manifest.version, manifest.sourceUrl, manifest.license, manifest.contentHash,
        manifest.signatureKeyId, manifest.signatureAlgorithm
    ).joinToString("|")
}
