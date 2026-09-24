package com.brain.workflow

import com.brain.events.InMemoryEventStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.Instant

class WorkflowIntegrationServiceTest {
    /** Porta falsa: reproduz só o contrato (Allowed/Blocked), sem nenhuma política própria. */
    private class FakePort(var blockReason: String? = null) : WorkflowRunPort {
        val executed = mutableListOf<String>()
        val triggers = mutableListOf<WorkflowTrigger>()

        override fun preflight(document: WorkflowDocument, runId: String, trigger: WorkflowTrigger): WorkflowPreflight {
            triggers += trigger
            val reason = blockReason
            return if (reason != null) WorkflowPreflight.Blocked(reason) else WorkflowPreflight.Allowed
        }

        override fun executeBody(
            document: WorkflowDocument,
            node: WorkflowNode,
            attempt: Int,
            runId: String,
            trigger: WorkflowTrigger
        ): WorkflowStepResult {
            executed += document.id
            return WorkflowStepResult(node.id, true, attempt)
        }
    }

    private class Env(blockReason: String? = null, var canStart: Boolean = true, seedScheduler: String? = null) {
        val root: File = Files.createTempDirectory("wf-integration").toFile()
        // Estado do scheduler gravado ANTES de o scheduler existir (simula scheduler.json corrompido/adulterado).
        private val seeded = seedScheduler?.let { File(root, "scheduler.json").writeText(it) }
        val catalog = WorkflowCatalog(root)
        val scheduler = WorkflowScheduler(File(root, "scheduler.json"))
        val engine = WorkflowEngine()
        val events = InMemoryEventStore()
        val port = FakePort(blockReason)
        val service = WorkflowIntegrationService(
            catalog, scheduler, engine, events, port,
            canStartRun = { canStart }
        )

        private fun file(id: String) = File(root, "available/custom/$id/WORKFLOW.md")

        private fun render(id: String, schedule: String?, bodyText: String) = buildString {
            appendLine("---")
            appendLine("name: $id")
            appendLine("version: 1.0.0")
            appendLine("description: teste")
            if (schedule != null) appendLine("schedule: $schedule")
            appendLine("---")
            appendLine(bodyText)
        }

        fun install(id: String, schedule: String? = null): WorkflowDocument {
            file(id).apply { parentFile.mkdirs() }.writeText(render(id, schedule, "# corpo"))
            return catalog.enable(id)
        }

        /** Reescreve o documento no disco (simula restore/override custom/edição). */
        fun rewrite(id: String, schedule: String?, bodyText: String) {
            file(id).writeText(render(id, schedule, bodyText))
        }

        fun close() { root.deleteRecursively() }
    }

    @Test
    fun `runWorkflow executa via engine real e registra eventos`() = runBlocking {
        val env = Env()
        try {
            env.install("demo")
            val result = env.service.runWorkflow("demo", "run-1")
            assertEquals(WorkflowStatus.COMPLETED, result.status)
            assertEquals(listOf("demo"), env.port.executed)
            assertEquals(listOf(WorkflowTrigger.MANUAL), env.port.triggers)
            val types = env.events.replay("run-1").map { it.type }
            assertEquals(listOf("workflow.started", "workflow.finished"), types)
        } finally { env.close() }
    }

    @Test
    fun `preflight bloqueado lanca SecurityException e nao executa o corpo`() = runBlocking {
        val env = Env(blockReason = "negado pela policy")
        try {
            env.install("demo")
            val failure = runCatching { env.service.runWorkflow("demo", "run-deny") }.exceptionOrNull()
            assertTrue(failure is SecurityException)
            assertTrue(failure!!.message!!.contains("negado pela policy"))
            assertTrue(env.port.executed.isEmpty())
            assertTrue(env.events.replay("run-deny").any { it.type == "workflow.error" })
        } finally { env.close() }
    }

    @Test
    fun `workflow desabilitado nao roda`() = runBlocking {
        val env = Env()
        try {
            env.install("demo")
            env.catalog.disable("demo")
            val failure = runCatching { env.service.runWorkflow("demo", "run-off") }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
            assertTrue(env.port.executed.isEmpty())
            assertTrue(env.port.triggers.isEmpty()) // nem chega a perguntar a porta
        } finally { env.close() }
    }

    @Test
    fun `tick executa schedule vencido uma unica vez como SCHEDULED e avanca o proximo horario`() = runBlocking {
        val env = Env()
        try {
            val doc = env.install("nightly", "every 1 hour")
            val t0 = Instant.parse("2026-09-23T12:00:00Z")
            val registered = env.scheduler.register(doc, "UTC", t0)!!

            assertTrue(env.service.tick(t0).isEmpty()) // ainda não venceu

            val due = registered.nextRun
            val first = env.service.tick(due)
            assertEquals(1, first.size)
            assertEquals(WorkflowStatus.COMPLETED, first.single().status)
            assertTrue(env.scheduler.get("nightly")!!.nextRun.isAfter(due))
            assertEquals(null, env.scheduler.get("nightly")!!.claimedBy)

            assertTrue(env.service.tick(due).isEmpty()) // segundo tick no mesmo instante não repete
            assertEquals(listOf("nightly"), env.port.executed)
            assertEquals(listOf(WorkflowTrigger.SCHEDULED), env.port.triggers)
        } finally { env.close() }
    }

    @Test
    fun `tick com falha avanca schedule e nao entra em retry a cada iteracao`() = runBlocking {
        val env = Env(blockReason = "negado pela policy")
        try {
            val doc = env.install("nightly", "every 1 hour")
            val t0 = Instant.parse("2026-09-23T12:00:00Z")
            val due = env.scheduler.register(doc, "UTC", t0)!!.nextRun

            val results = env.service.tick(due)
            assertEquals(WorkflowStatus.FAILED, results.single().status)
            assertFalse(env.scheduler.get("nightly")!!.nextRun.isBefore(due.plusSeconds(1)))
            assertTrue(env.service.tick(due).isEmpty())
            assertTrue(env.port.executed.isEmpty())
        } finally { env.close() }
    }

    @Test
    fun `tick revoga o schedule quando o conteudo mudou desde o registro`() = runBlocking {
        val env = Env()
        try {
            val doc = env.install("nightly", "every 1 hour")
            val t0 = Instant.parse("2026-09-23T12:00:00Z")
            val due = env.scheduler.register(doc, "UTC", t0)!!.nextRun

            // restore / override custom / edição: mesmo id e versão, corpo diferente
            env.rewrite("nightly", "every 1 hour", "# corpo alterado")

            assertTrue(env.service.tick(due).isEmpty())
            assertNull(env.scheduler.get("nightly"))
            assertTrue(env.port.executed.isEmpty())
            assertTrue(env.port.triggers.isEmpty())
            assertTrue(env.events.replay("scheduler").any { it.type == "workflow.schedule.revoked" })
        } finally { env.close() }
    }

    @Test
    fun `schedule com id invalido e revogado e nao trava os demais`() = runBlocking {
        val poison = """[{"id":"../evil","version":"1.0.0","expression":"every 1 hour","zoneId":"UTC","nextRun":"2026-09-23T10:00:00Z","claimedBy":null,"claimUntil":null,"contentHash":"x"}]"""
        val env = Env(seedScheduler = poison)
        try {
            val doc = env.install("nightly", "every 1 hour")
            val t0 = Instant.parse("2026-09-23T12:00:00Z")
            val due = env.scheduler.register(doc, "UTC", t0)!!.nextRun

            val results = env.service.tick(due)
            assertEquals(1, results.size)
            assertEquals(WorkflowStatus.COMPLETED, results.single().status)
            assertNull(env.scheduler.get("../evil"))
            assertEquals(listOf("nightly"), env.port.executed)
            assertTrue(env.events.replay("scheduler").any { it.type == "workflow.schedule.revoked" })
        } finally { env.close() }
    }

    @Test
    fun `runWorkflow com pin diferente do documento resolvido bloqueia antes da porta`() = runBlocking {
        val env = Env()
        try {
            env.install("demo", "every 1 hour")
            val failure = runCatching {
                env.service.runWorkflow("demo", "run-pin", trigger = WorkflowTrigger.SCHEDULED, expectedContentHash = "hash-antigo")
            }.exceptionOrNull()
            assertTrue(failure is SecurityException)
            assertTrue(env.port.triggers.isEmpty())
            assertTrue(env.port.executed.isEmpty())
            assertTrue(env.events.replay("run-pin").any { it.type == "workflow.error" })
        } finally { env.close() }
    }

    @Test
    fun `tick adia sem consumir o schedule quando o sandbox esta ocupado`() = runBlocking {
        val env = Env(canStart = false)
        try {
            val doc = env.install("nightly", "every 1 hour")
            val t0 = Instant.parse("2026-09-23T12:00:00Z")
            val due = env.scheduler.register(doc, "UTC", t0)!!.nextRun

            assertTrue(env.service.tick(due).isEmpty())
            assertEquals(due, env.scheduler.get("nightly")!!.nextRun)
            assertNull(env.scheduler.get("nightly")!!.claimedBy)
            assertTrue(env.port.executed.isEmpty())

            env.canStart = true
            assertEquals(1, env.service.tick(due).size)
            assertEquals(listOf("nightly"), env.port.executed)
        } finally { env.close() }
    }
}
