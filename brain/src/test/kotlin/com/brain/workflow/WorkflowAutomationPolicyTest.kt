package com.brain.workflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneId

class WorkflowAutomationPolicyTest {
    private val now = Instant.parse("2026-09-23T12:00:00Z")
    private val utc = ZoneId.of("UTC")

    private fun doc(schedule: String? = null, extraFrontmatter: String = ""): WorkflowDocument {
        val content = buildString {
            appendLine("---")
            appendLine("name: demo")
            appendLine("version: 1.0.0")
            appendLine("description: teste")
            if (schedule != null) appendLine("schedule: $schedule")
            if (extraFrontmatter.isNotBlank()) appendLine(extraFrontmatter)
            appendLine("---")
            appendLine("# corpo")
        }
        return WorkflowDocumentParser.parse(content, WorkflowSource.CUSTOM)
    }

    @Test
    fun `documento somente instrucao e elegivel`() {
        assertNull(WorkflowAutomationPolicy.executionRefusal(doc()))
        assertNull(WorkflowAutomationPolicy.executionRefusal(doc(extraFrontmatter = "permissions: []\ntools: []\neffects: []")))
    }

    @Test
    fun `documento que declara permissions tools ou effects nao roda`() {
        assertNotNull(WorkflowAutomationPolicy.executionRefusal(doc(extraFrontmatter = "permissions: [network]")))
        assertNotNull(WorkflowAutomationPolicy.executionRefusal(doc(extraFrontmatter = "tools: [bash]")))
        assertNotNull(WorkflowAutomationPolicy.executionRefusal(doc(extraFrontmatter = "effects: [write_file]")))
    }

    @Test
    fun `schedule abaixo do piso e recusado e acima e aceito`() {
        assertNotNull(WorkflowAutomationPolicy.scheduleRefusal(doc("every 1 minutes"), utc, now))
        assertNotNull(WorkflowAutomationPolicy.scheduleRefusal(doc("every 14 minutes"), utc, now))
        assertNull(WorkflowAutomationPolicy.scheduleRefusal(doc("every 15 minutes"), utc, now))
        assertNull(WorkflowAutomationPolicy.scheduleRefusal(doc("every 1 hour"), utc, now))
        assertNull(WorkflowAutomationPolicy.scheduleRefusal(doc("daily 03:00"), utc, now))
    }

    @Test
    fun `on-demand nao tem o que recusar`() {
        assertNull(WorkflowAutomationPolicy.scheduleRefusal(doc("on-demand"), utc, now))
        assertNull(WorkflowAutomationPolicy.scheduleRefusal(doc(), utc, now))
    }

    @Test
    fun `schedule de documento com effects e recusado`() {
        assertNotNull(WorkflowAutomationPolicy.scheduleRefusal(doc("every 1 hour", "effects: [write_file]"), utc, now))
    }

    @Test
    fun `pin recusa conteudo diferente versao diferente e pin em branco`() {
        val original = doc("every 1 hour")
        val pinned = ScheduledWorkflow("demo", "1.0.0", "every 1 hour", "UTC", now, contentHash = original.contentHash)
        assertNull(WorkflowAutomationPolicy.pinRefusal(pinned, original))

        val edited = original.copy(contentHash = "outro-hash")
        assertNotNull(WorkflowAutomationPolicy.pinRefusal(pinned, edited))

        val bumped = original.copy(version = "1.0.1")
        assertNotNull(WorkflowAutomationPolicy.pinRefusal(pinned, bumped))

        val legacy = pinned.copy(contentHash = "")
        assertNotNull(WorkflowAutomationPolicy.pinRefusal(legacy, original))
    }

    @Test
    fun `scheduler recusa registrar e remove registro antigo quando o documento passa a declarar effects`() {
        val state = Files.createTempFile("wf-policy-scheduler", ".json").toFile()
        try {
            val scheduler = WorkflowScheduler(state)
            assertNotNull(scheduler.register(doc("every 1 hour"), "UTC", now))
            assertNull(scheduler.register(doc("every 1 hour", "effects: [write_file]"), "UTC", now))
            assertNull(scheduler.get("demo"))
            assertNull(scheduler.register(doc("every 1 minutes"), "UTC", now))
        } finally { state.delete() }
    }

    @Test
    fun `scheduler persiste e recarrega o pin de conteudo`() {
        val state = File.createTempFile("wf-policy-pin", ".json")
        try {
            val document = doc("every 1 hour")
            WorkflowScheduler(state).register(document, "UTC", now)
            val reloaded = WorkflowScheduler(state).get("demo")!!
            assertEquals(document.contentHash, reloaded.contentHash)
            assertTrue(reloaded.contentHash.isNotBlank())
        } finally { state.delete() }
    }
}
