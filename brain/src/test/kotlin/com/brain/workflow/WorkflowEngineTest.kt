package com.brain.workflow

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowEngineTest {
    @Test fun `retry e dependências concluem workflow`() {
        val engine = WorkflowEngine()
        val manifest = WorkflowManifest("build", "1.0.0", listOf(
            WorkflowNode("compile", "workspace.write", retryLimit = 1),
            WorkflowNode("test", "sandbox.execute", dependencies = setOf("compile"))
        ))
        val calls = mutableMapOf<String, Int>()
        val result = engine.run(manifest, "run-1", "key-1", { true }) { node, attempt ->
            calls[node.id] = (calls[node.id] ?: 0) + 1
            WorkflowStepResult(node.id, node.id == "test" || attempt == 2, attempt)
        }
        assertEquals(WorkflowStatus.COMPLETED, result.status)
        assertEquals(2, calls["compile"])
        assertEquals(1, calls["test"])
    }

    @Test(expected = SecurityException::class)
    fun `capability não autorizada bloqueia workflow`() {
        WorkflowEngine().run(
            WorkflowManifest("wf", "1", listOf(WorkflowNode("a", "danger"))),
            "run", "key", { false }, { _, _ -> WorkflowStepResult("a", true, 1) }
        )
    }

    @Test fun `estado persistido torna retry idempotente`() {
        val file = Files.createTempFile("workflow", ".json").toFile()
        try {
            val manifest = WorkflowManifest("wf", "1", listOf(WorkflowNode("a", "read")))
            val first = WorkflowEngine(file).run(manifest, "run", "key", { true }) { node, attempt -> WorkflowStepResult(node.id, true, attempt) }
            var executed = false
            val second = WorkflowEngine(file).run(manifest, "run", "key", { true }) { _, _ -> executed = true; WorkflowStepResult("a", true, 1) }
            assertEquals(first.status, second.status)
            assertTrue(!executed)
        } finally { file.delete() }
    }

    @Test(expected = IllegalStateException::class)
    fun `lease impede owner concorrente`() {
        val file = Files.createTempFile("workflow-lease", ".json").toFile()
        try {
            val leases = WorkflowLeaseStore(file, ttlMs = 60_000)
            leases.acquire("wf", "worker-a")
            leases.acquire("wf", "worker-b")
        } finally { file.delete() }
    }
}
