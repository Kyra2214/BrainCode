package com.brain.policy

import java.nio.file.Files
import java.time.Instant
import java.time.temporal.ChronoUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ApprovalStoreTest {
    @Test fun `aprovacao pode ser decidida e consumida uma vez`() {
        val file = Files.createTempFile("approvals", ".jsonl").toFile()
        try {
            val store = FileApprovalStore(file)
            val request = ApprovalRequest(runId = "r", taskId = "t", capability = "x", resource = "res", expiresAt = Instant.now().plus(1, ChronoUnit.MINUTES))
            store.create(request)
            assertEquals(ApprovalStatus.APPROVED, store.decide(request.id, true)!!.status)
            assertEquals(ApprovalStatus.CONSUMED, store.consume(request.id)!!.status)
            assertNull(store.consume(request.id))
        } finally { file.delete() }
    }

    @Test fun `aprovacao expirada nao pode ser criada`() {
        val file = Files.createTempFile("approvals", ".jsonl").toFile()
        try {
            val store = FileApprovalStore(file)
            val request = ApprovalRequest(runId = "r", taskId = "t", capability = "x", resource = "res", expiresAt = Instant.now().minusSeconds(1))
            org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { store.create(request) }
        } finally { file.delete() }
    }
}
