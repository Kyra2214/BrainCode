package com.sandbox.sandbox

import com.sandbox.runtime.ExecutionLog
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureCommandExecutorTest {
    private class Delegate : SandboxCommandExecutor {
        override fun execute(command: List<String>, timeoutSeconds: Long, workingDir: String): ExecutionLog =
            error("delegate must not be reached")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `recusa bash c antes do delegate`() {
        SecureCommandExecutor(Delegate()).execute(listOf("bash", "-c", "dd if=/dev/zero of=/tmp/x"), 10, "/tmp")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `recusa comando bloqueado escondido nos argumentos`() {
        SecureCommandExecutor(Delegate()).execute(listOf("tool", "--script", "mount -t tmpfs none /tmp/m"), 10, "/tmp")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `recusa shell alternativo`() {
        SecureCommandExecutor(Delegate()).execute(listOf("sh", "-c", "echo ok"), 10, "/tmp")
    }
}
