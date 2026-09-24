package com.brain.job

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class JobStoreTest {
    @Test
    fun `job persiste estado evidencias payload e tentativas apos reinicio`() {
        val file = Files.createTempFile("brain-job", ".json").toFile()
        try {
            val first = JobStore(file)
            first.create("job-1", "run-1", "task-1", mapOf("capability" to "build"))
            first.transition("job-1", JobStatus.QUEUED)
            first.transition("job-1", JobStatus.RUNNING)
            first.appendEvidence("job-1", "log:started", "artifact:output.zip")
            first.transition("job-1", JobStatus.SUCCEEDED)

            val restored = JobStore(file).get("job-1")!!
            assertEquals(JobStatus.SUCCEEDED, restored.status)
            assertEquals(1, restored.attempts)
            assertEquals(mapOf("capability" to "build"), restored.payload)
            assertEquals(listOf("log:started", "artifact:output.zip"), restored.evidence)
        } finally { file.delete() }
    }

    @Test
    fun `retry incrementa tentativa e permite voltar para fila`() {
        val file = Files.createTempFile("brain-job-retry", ".json").toFile()
        try {
            val store = JobStore(file)
            store.create("job-1", "run-1", "task-1")
            store.transition("job-1", JobStatus.QUEUED)
            store.transition("job-1", JobStatus.RUNNING)
            val retrying = store.transition("job-1", JobStatus.RETRYING, error = "timeout")
            store.transition("job-1", JobStatus.QUEUED)

            assertEquals(2, retrying.attempts)
            assertEquals(JobStatus.QUEUED, store.get("job-1")?.status)
        } finally { file.delete() }
    }

    @Test
    fun `transicao de estado terminal e rejeitada`() {
        val file = Files.createTempFile("brain-job-terminal", ".json").toFile()
        try {
            val store = JobStore(file)
            store.create("job-1", "run-1", "task-1")
            store.transition("job-1", JobStatus.CANCELLED)

            assertThrows(IllegalArgumentException::class.java) {
                store.transition("job-1", JobStatus.RUNNING)
            }
            assertTrue(store.all().single().status == JobStatus.CANCELLED)
        } finally { file.delete() }
    }
}
