package com.brain.workflow

import java.nio.file.Files
import java.time.Instant
import java.time.ZoneOffset
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

    private fun frontmatter(name: String = "review-prs", description: String) = """
        ---
        name: $name
        version: 1.0.0
        description: $description
        ---

        # Body
    """.trimIndent()
}
