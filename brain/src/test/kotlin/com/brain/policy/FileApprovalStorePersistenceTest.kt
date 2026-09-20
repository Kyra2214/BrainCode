package com.brain.policy

import java.io.File
import java.nio.file.Files
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class FileApprovalStorePersistenceTest {
    @Test
    fun `approval aprovada sobrevive a nova instancia`() {
        val file = Files.createTempFile("approvals-", ".jsonl").toFile()
        try {
            val request = ApprovalRequest("create-1", "run-1", "task-1", "workspace.generate", "create:task-1", Instant.now().plusSeconds(600))
            FileApprovalStore(file).create(request)
            assertEquals(ApprovalStatus.APPROVED, FileApprovalStore(file).decide(request.id, true)!!.status)
            assertNotNull(FileApprovalStore(file).get(request.id))
            assertEquals(ApprovalStatus.APPROVED, FileApprovalStore(file).get(request.id)!!.status)
        } finally {
            file.delete()
        }
    }
}
