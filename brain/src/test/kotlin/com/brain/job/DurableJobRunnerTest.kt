package com.brain.job

import com.brain.workflow.WorkflowEngine
import com.brain.workflow.WorkflowManifest
import com.brain.workflow.WorkflowNode
import com.brain.workflow.WorkflowStepResult
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DurableJobRunnerTest {
    @Test
    fun `runner persiste sucesso e evidencias no job store`() {
        val file = Files.createTempFile("durable-job", ".json").toFile()
        try {
            val store = JobStore(file)
            val record = DurableJobRunner(store, WorkflowEngine()).run(
                "job-1", "run-1", "task-1", WorkflowManifest("wf", "1", listOf(WorkflowNode("a", "read"))), { true }
            , execute = { node, attempt -> WorkflowStepResult(node.id, true, attempt, evidence = listOf("evidence:a")) })
            assertEquals(JobStatus.SUCCEEDED, record.status)
            assertTrue(record.evidence.contains("evidence:a"))
        } finally { file.delete() }
    }
}
