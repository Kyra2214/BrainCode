package com.sandbox.sandbox

import com.sandbox.runtime.ExecutionLog
import com.sandbox.runtime.SandboxState
import com.sandbox.runtime.TerminationReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class ToolchainModelsTest {
    private class Executor(private val success: Boolean) : SandboxCommandExecutor {
        var lastCommand: List<String>? = null
        override fun execute(command: List<String>, timeoutSeconds: Long, workingDir: String): ExecutionLog {
            lastCommand = command
            return ExecutionLog(
                executionId = "test", sessionId = "test", command = command, workingDir = workingDir,
                startedAt = 0, finishedAt = 1, durationMs = 1,
                exitCode = if (success) 0 else 1, terminationReason = TerminationReason.PROCESS_EXIT,
                timedOut = false, forcedKill = false, stdout = if (success) "tool 1.2.3" else "",
                stderr = if (success) "" else "missing", sandboxState = SandboxState.READY
            )
        }
    }

    @Test
    fun `detecta Java com probe de baixa memoria`() {
        val executor = Executor(true)
        val profile = BuiltInToolchains.all.first { it.id == "java" }
        val result = ToolchainDetector(executor).detect(profile)

        assertTrue(result.installed)
        assertEquals(listOf("java", "--version"), executor.lastCommand)
        assertEquals("tool 1.2.3", result.versionOutput)
    }

    @Test
    fun `falha de deteccao produz diagnostico sem lancar excecao`() {
        val executor = Executor(false)
        val profile = BuiltInToolchains.all.first { it.id == "rust" }
        val result = ToolchainDetector(executor).detect(profile)

        assertFalse(result.installed)
        assertEquals("missing", result.diagnostic)
    }

    @Test
    fun `plano atualiza indice vazio e instala somente pacotes declarados`() {
        val detector = ToolchainDetector(Executor(true))
        val profile = BuiltInToolchains.all.first { it.id == "python" }
        val plan = detector.planInstall(profile)

        assertEquals(listOf("bash", "-c"), plan.command.take(2))
        val script = plan.command[2]
        assertTrue(script.contains("find /var/lib/apt/lists"))
        assertTrue(script.contains("apt-get update -qq"))
        assertTrue(script.contains("--fix-missing"))
        assertTrue(script.endsWith("python3 python3-pip 2>&1 | tail -n 120"))
        assertFalse(script.contains("python3;"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `nao aceita pacote com metacaracter de shell`() {
        ToolchainProfile("unsafe", ToolchainKind.JAVA, "Unsafe", "java", listOf("--version"), listOf("java; rm -rf /"))
    }

    @Test
    fun `manager persiste instalacao e permite remocao`() {
        val executor = Executor(true)
        val profile = BuiltInToolchains.all.first { it.id == "java" }
        val manager = ToolchainManager(executor, Files.createTempDirectory("toolchains").toFile(), listOf(profile))

        assertEquals(ToolchainState.INSTALLED, manager.install("java").state)
        assertEquals(ToolchainState.INSTALLED, manager.status("java").state)
        assertEquals(ToolchainState.NOT_INSTALLED, manager.remove("java").state)
    }
}
