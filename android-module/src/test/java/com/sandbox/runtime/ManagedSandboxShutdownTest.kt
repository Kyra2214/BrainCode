package com.sandbox.runtime

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class ManagedSandboxShutdownTest {
    @Test fun `shutdown during execution stops process and persists evidence`() {
        val dir = Files.createTempDirectory("sandbox-shutdown-").toFile()
        try {
            val repo = FileExecutionLogRepository(dir)
            val runtime = ManagedSandboxRuntime(
                TestLauncher(), repo, sessionId = "shutdown-session"
            )
            val done = CountDownLatch(1)
            var result: ExecutionLog? = null
            thread {
                result = runtime.execute(listOf("sleep", "10"), timeoutSeconds = 30)
                done.countDown()
            }
            Thread.sleep(150)
            assertTrue(runtime.shutdown())
            assertTrue(done.await(3, TimeUnit.SECONDS))
            assertEquals(TerminationReason.SHUTDOWN, result?.terminationReason)
            assertEquals(SandboxState.CLOSED, runtime.state)
            assertNotNull(repo.get(result!!.executionId))
        } finally { dir.deleteRecursively() }
    }

    private class TestLauncher : SandboxProcessLauncher {
        override fun launch(command: List<String>, workingDir: String): Process = ProcessBuilder(command).start()
    }
}
