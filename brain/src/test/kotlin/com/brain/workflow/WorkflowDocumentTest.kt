package com.brain.workflow

import java.nio.file.Files
import java.time.Instant
import java.time.ZoneOffset
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowDocumentTest {
    private fun document(source: WorkflowSource = WorkflowSource.COMMUNITY, description: String = "Community version") =
        WorkflowDocumentParser.parse(
            """
                ---
                name: review-prs
                version: 1.2.0
                description: $description
                author: team
                schedule: every 2 hours
                required_permissions: [github.read]
                tools: [gh]
                effects: [read]
                ---

                # Review PRs
                Read-only instructions.
            """.trimIndent(), source, "/tmp/${source.name}/WORKFLOW.md"
        )

    @Test
    fun `parser preserva manifesto corpo hash e declaracoes`() {
        val doc = document()
        assertEquals("review-prs", doc.id)
        assertEquals("1.2.0", doc.version)
        assertEquals(setOf("github.read"), doc.requiredPermissions)
        assertEquals(setOf("gh"), doc.tools)
        assertEquals(64, doc.contentHash.length)
        assertTrue(doc.body.contains("Read-only instructions"))
    }

    @Test
    fun `schedule calcula proxima execucao e on demand nao agenda`() {
        val now = Instant.parse("2026-09-23T12:00:00Z")
        assertEquals("2026-09-23T14:00:00Z", WorkflowSchedule("every 2 hours").nextAfter(now, ZoneOffset.UTC).toString())
        assertEquals(null, WorkflowSchedule("on-demand").nextAfter(now, ZoneOffset.UTC))
    }

    @Test
    fun `catalogo prefere custom e habilitacao nao executa`() {
        val root = Files.createTempDirectory("workflow-catalog").toFile()
        try {
            val community = root.resolve("available/community/review-prs/WORKFLOW.md")
            val custom = root.resolve("available/custom/review-prs/WORKFLOW.md")
            community.parentFile.mkdirs(); custom.parentFile.mkdirs()
            community.writeText(frontmatter(description = "Community version"))
            custom.writeText(frontmatter(description = "Custom version"))
            val catalog = WorkflowCatalog(root)
            assertEquals("Custom version", catalog.resolve("review-prs")?.description)
            assertFalse(catalog.isEnabled("review-prs"))
            catalog.enable("review-prs")
            assertTrue(catalog.isEnabled("review-prs"))
            catalog.disable("review-prs")
            assertFalse(catalog.isEnabled("review-prs"))
            assertTrue(custom.exists())
        } finally { root.deleteRecursively() }
    }

    @Test
    fun `backup restaura apenas estado e arquivos customizados`() {
        val root = Files.createTempDirectory("workflow-backup").toFile()
        val restored = Files.createTempDirectory("workflow-restore").toFile()
        try {
            val custom = root.resolve("available/custom/local/WORKFLOW.md")
            custom.parentFile.mkdirs(); custom.writeText(frontmatter("local", "Local"))
            WorkflowCatalog(root).enable("local")
            val backup = root.resolve("backup.zip")
            WorkflowCatalog(root).backup(backup)
            WorkflowCatalog(restored).restore(backup)
            assertEquals("Local", WorkflowCatalog(restored).resolve("local")?.description)
            assertTrue(WorkflowCatalog(restored).isEnabled("local"))
        } finally { root.deleteRecursively(); restored.deleteRecursively() }
    }

    @Test
    fun `runDocument usa workflow run e entrega corpo ao executor autorizado`() {
        val doc = document(WorkflowSource.CUSTOM, "Body is data")
        val engine = WorkflowEngine()
        var authorizedCapability = ""
        var receivedBody = ""
        val result = engine.runDocument(
            document = doc,
            runId = "run-document",
            idempotencyKey = "idem-document",
            authorize = { capability -> authorizedCapability = capability; capability == "workflow.run" },
            executeBody = { body, node, attempt ->
                receivedBody = body
                assertEquals("workflow.run", node.capability)
                WorkflowStepResult(node.id, success = true, attempts = attempt, evidence = listOf("workflow-body:received"))
            }
        )

        assertEquals(WorkflowStatus.COMPLETED, result.status)
        assertEquals("workflow.run", authorizedCapability)
        assertTrue(receivedBody.contains("Read-only instructions."))
        assertTrue(result.steps.single().evidence.contains("workflow-body:received"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `restore rejeita zip slip`() {
        val root = Files.createTempDirectory("workflow-zip-slip").toFile()
        val backup = root.resolve("malicious.zip")
        try {
            writeZip(backup, mapOf(
                "catalog-state.json" to emptyState().toString(),
                "../escape/WORKFLOW.md" to frontmatter("escape", "Escape")
            ))
            WorkflowCatalog(root).restore(backup)
        } finally { root.deleteRecursively() }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `restore rejeita caminho absoluto dentro de custom`() {
        val root = Files.createTempDirectory("workflow-absolute").toFile()
        val backup = root.resolve("malicious.zip")
        try {
            writeZip(backup, mapOf(
                "catalog-state.json" to emptyState().toString(),
                "custom//tmp/WORKFLOW.md" to frontmatter("absolute", "Absolute")
            ))
            WorkflowCatalog(root).restore(backup)
        } finally { root.deleteRecursively() }
    }

    @Test
    fun `restore rejeita hash adulterado e preserva catalogo anterior`() {
        val root = Files.createTempDirectory("workflow-hash").toFile()
        try {
            val original = frontmatter("local", "Original")
            val existing = root.resolve("available/custom/local/WORKFLOW.md")
            existing.parentFile.mkdirs()
            existing.writeText(original)
            WorkflowCatalog(root).enable("local")
            val expectedHash = WorkflowDocumentParser.parse(original, WorkflowSource.CUSTOM).contentHash
            val state = JSONObject()
                .put("schemaVersion", 1)
                .put("enabled", JSONArray(listOf("local")))
                .put("workflows", JSONArray(listOf(JSONObject().put("id", "local").put("version", "1.0.0").put("source", "CUSTOM").put("contentHash", expectedHash))))
            val backup = root.resolve("tampered.zip")
            writeZip(backup, mapOf(
                "catalog-state.json" to state.toString(),
                "custom/local/WORKFLOW.md" to frontmatter("local", "Tampered")
            ))

            val failure = runCatching { WorkflowCatalog(root).restore(backup) }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
            assertEquals(original, existing.readText())
            assertEquals("Original", WorkflowCatalog(root).resolve("local")?.description)
        } finally { root.deleteRecursively() }
    }

    private fun emptyState() = JSONObject()
        .put("schemaVersion", 1)
        .put("enabled", JSONArray())
        .put("workflows", JSONArray())

    private fun writeZip(file: java.io.File, entries: Map<String, String>) {
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(StandardCharsets.UTF_8))
                zip.closeEntry()
            }
        }
    }

    private fun frontmatter(name: String = "review-prs", description: String) = """
        ---
        name: $name
        version: 1.0.0
        description: $description
        ---

        # Body
    """.trimIndent()
}
