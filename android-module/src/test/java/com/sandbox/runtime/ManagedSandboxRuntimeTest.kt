package com.sandbox.runtime

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

class ManagedSandboxRuntimeTest {
    @Test fun `successful execution is persisted with output and exit code`() {
        val dir = Files.createTempDirectory("sandbox-log-").toFile()
        try {
            val repo = FileExecutionLogRepository(dir)
            val runtime = ManagedSandboxRuntime(TestLauncher(), repo, sessionId = "session-1")
            val log = runtime.execute(listOf("printf", "hello"), timeoutSeconds = 5)
            assertEquals(TerminationReason.PROCESS_EXIT, log.terminationReason)
            assertEquals(0, log.exitCode)
            assertTrue(log.succeeded)
            assertEquals("hello", log.stdout.trim())
            assertEquals(log.executionId, repo.get(log.executionId)?.executionId)
        } finally { dir.deleteRecursively() }
    }

    @Test fun `timeout preserves bounded partial output`() {
        val dir = Files.createTempDirectory("sandbox-log-").toFile()
        try {
            val repo = FileExecutionLogRepository(dir, maxOutputChars = 64)
            val runtime = ManagedSandboxRuntime(TestLauncher(), repo, sessionId = "session-2", maxOutputChars = 64)
            val log = runtime.execute(listOf("sh", "-c", "printf 1234567890; sleep 3"), timeoutSeconds = 1)
            assertEquals(TerminationReason.TIMEOUT, log.terminationReason)
            assertTrue(log.timedOut)
            assertTrue(log.forcedKill || log.exitCode == null)
            assertTrue(log.stdout.length < 128)
            assertTrue(log.stdout.contains("1234567890"))
        } finally { dir.deleteRecursively() }
    }

    @Test fun `cancel stops an active execution and records cancellation`() {
        val dir = Files.createTempDirectory("sandbox-log-").toFile()
        try {
            val repo = FileExecutionLogRepository(dir)
            val runtime = ManagedSandboxRuntime(TestLauncher(), repo, sessionId = "session-3")
            val done = CountDownLatch(1)
            var log: ExecutionLog? = null
            thread {
                log = runtime.execute(listOf("sleep", "10"), timeoutSeconds = 30)
                done.countDown()
            }
            Thread.sleep(150)
            assertTrue(runtime.cancel())
            assertTrue(done.await(3, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(TerminationReason.CANCELLED, log?.terminationReason)
            assertNull(log?.exitCode)
        } finally { dir.deleteRecursively() }
    }

    @Test fun `reset does not delete historical evidence`() {
        val dir = Files.createTempDirectory("sandbox-log-").toFile()
        try {
            val repo = FileExecutionLogRepository(dir)
            val runtime = ManagedSandboxRuntime(TestLauncher(), repo, sessionId = "session-4")
            val log = runtime.execute(listOf("echo", "keep-me"), timeoutSeconds = 5)
            var resetCalled = false
            runtime.reset { resetCalled = true }
            assertTrue(resetCalled)
            assertEquals("keep-me", repo.get(log.executionId)?.stdout?.trim())
            assertEquals(SandboxState.READY, runtime.state)
        } finally { dir.deleteRecursively() }
    }

    private class TestLauncher : SandboxProcessLauncher {
        override fun launch(command: List<String>, workingDir: String): Process =
            ProcessBuilder(command).directory(File(workingDir).takeIf { it.isDirectory }).start()
    }
}
