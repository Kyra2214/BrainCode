package com.sandbox.runtime

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class FileExecutionLogRepositoryTest {
    @Test fun `secrets are redacted and output is bounded`() {
        val dir = Files.createTempDirectory("sandbox-store-").toFile()
        try {
            val repo = FileExecutionLogRepository(dir, maxLogs = 10, maxOutputChars = 16)
            val log = ExecutionLog("id1", "s1", listOf("curl", "token=SUPERSECRET"), "/home/sandbox",
                1, 2, 1, 1, TerminationReason.PROCESS_EXIT, false, false,
                "12345678901234567890", "password=hunter2", SandboxState.READY)
            repo.save(log)
            val loaded = repo.get("id1")!!
            assertFalse(loaded.command.any { it.contains("SUPERSECRET") })
            assertFalse(loaded.stderr.contains("hunter2"))
            assertTrue(loaded.stdout.contains("output truncated"))
            assertTrue(loaded.outputTruncated)
        } finally { dir.deleteRecursively() }
    }

    @Test fun `running marker is recovered as interrupted`() {
        val dir = Files.createTempDirectory("sandbox-store-").toFile()
        try {
            val repo = FileExecutionLogRepository(dir)
            repo.save(ExecutionLog("id2", "s2", listOf("sleep", "99"), "/home/sandbox",
                100, 0, 0, null, TerminationReason.RUNTIME_ERROR, false, false, "", "", SandboxState.RUNNING))
            val recovered = repo.recoverRunning("s2", 250)
            assertEquals(1, recovered.size)
            assertEquals(TerminationReason.INTERRUPTED, recovered.single().terminationReason)
            assertTrue(recovered.single().forcedKill)
            assertEquals(250, repo.get("id2")!!.finishedAt)
        } finally { dir.deleteRecursively() }
    }
}
