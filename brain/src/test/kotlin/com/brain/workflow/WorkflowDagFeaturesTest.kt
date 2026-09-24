package com.brain.workflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowDagFeaturesTest {
    @Test
    fun `nodes independentes executam em lote e evidencias sao preservadas`() {
        val engine = WorkflowEngine()
        val result = engine.run(
            WorkflowManifest(
                "parallel", "1", listOf(
                    WorkflowNode("a", "read"),
                    WorkflowNode("b", "read"),
                    WorkflowNode("c", "join", dependencies = setOf("a", "b"))
                ), maxParallelism = 2
            ),
            "run", "parallel-key", { true }
        ) { node, attempt ->
            WorkflowStepResult(node.id, true, attempt, evidence = listOf("evidence:${node.id}"))
        }

        assertEquals(WorkflowStatus.COMPLETED, result.status)
        assertEquals(setOf("a", "b", "c"), result.steps.map { it.nodeId }.toSet())
        assertTrue(result.steps.all { it.evidence.single().startsWith("evidence:") })
    }

    @Test
    fun `cancelamento nao executa nodes pendentes`() {
        var calls = 0
        val result = WorkflowEngine().run(
            WorkflowManifest("cancel", "1", listOf(WorkflowNode("a", "read"))),
            "run", "cancel-key", { true },
            execute = { _, _ -> calls += 1; WorkflowStepResult("a", true, 1) },
            isCancelled = { true }
        )

        assertEquals(WorkflowStatus.CANCELLED, result.status)
        assertEquals(0, calls)
    }

    @Test
    fun `timeout gera status dedicado mesmo quando executor retorna sucesso tarde`() {
        val result = WorkflowEngine().run(
            WorkflowManifest("timeout", "1", listOf(WorkflowNode("a", "read", timeoutMs = 1))),
            "run", "timeout-key", { true }
        ) { node, attempt ->
            Thread.sleep(10)
            WorkflowStepResult(node.id, true, attempt, evidence = listOf("raw"))
        }

        assertEquals(WorkflowStatus.TIMED_OUT, result.status)
        assertTrue(result.steps.single().evidence.contains("timeout:a"))
    }
}
