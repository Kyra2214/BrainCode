package com.sandbox.runtime

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Estas execuções rodam `bash` diretamente no host de CI (sem `proot`,
 * indisponível fora de Android) — mas usam exatamente a mesma string que
 * [ProotResourceLimits.verifiedPreamble] gera, a mesma que
 * [ProotProcessLauncher] e [SandboxRuntime] embutem no `/bin/bash -c` que o
 * `proot` exec'a. Isso prova o mecanismo (setrlimit real, sobrevive a
 * fork/exec) sem depender de um device/emulador Android, que continua fora
 * do escopo testável neste ambiente (sem SDK/emulador — ver
 * AUDITORIA_PESADA.md).
 */
class ProotResourceLimitsTest {

    private fun runBash(script: String, timeoutSeconds: Long = 10): Triple<Int, String, String> {
        val process = ProcessBuilder("/bin/bash", "-c", script).redirectErrorStream(false).start()
        val out = process.inputStream.bufferedReader().readText()
        val err = process.errorStream.bufferedReader().readText()
        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) process.destroyForcibly()
        return Triple(if (finished) process.exitValue() else -1, out, err)
    }

    @Test fun `ulimitStatements converts bytes to bash 1024-byte blocks`() {
        val limits = ProotResourceLimits(maxMemoryBytes = 64L * 1024 * 1024, maxFileSizeBytes = 1024L * 1024)
        assertEquals(listOf("ulimit -v 65536", "ulimit -f 1024"), limits.ulimitStatements())
    }

    @Test fun `DEFAULT activates maxProcesses with tree watchdog support`() {
        assertEquals(128, ProotResourceLimits.DEFAULT.maxProcesses)
        assertTrue(ProotResourceLimits.DEFAULT.hasLimits())
    }

    @Test fun `NONE produces empty preamble`() {
        assertEquals("", ProotResourceLimits.NONE.verifiedPreamble())
        assertFalse(ProotResourceLimits.NONE.hasLimits())
    }

    @Test fun `verifiedPreamble memory limit prevents unrestricted allocation`() {
        val limits = ProotResourceLimits(maxMemoryBytes = 64L * 1024 * 1024)
        val script = limits.verifiedPreamble() + "exec python3 -c \"a=bytearray(200*1024*1024); print('NO_OOM')\""
        val (exitCode, out, err) = runBash(script)
        assertTrue("allocation unexpectedly succeeded: exit=$exitCode stdout=$out", !out.contains("NO_OOM"))
        val (cleaned, parsed) = ResourceLimitVerification.extract(err)
        assertNotNull("expected verification marker in stderr, got: $err", parsed)
        assertTrue(ResourceLimitVerification.matches(limits, parsed))
        assertFalse("marker line must not leak into cleaned stderr", cleaned.contains(ProotResourceLimits.MARKER))
    }

    @Test fun `verifiedPreamble cpu limit kills a runaway loop`() {
        val limits = ProotResourceLimits(maxCpuSeconds = 1)
        val script = limits.verifiedPreamble() + "exec python3 -c \"while True: pass\""
        val (exitCode, _, err) = runBash(script, timeoutSeconds = 5)
        // RLIMIT_CPU com soft==hard mata com SIGKILL (137) após o grace period do kernel.
        assertTrue("exit code was $exitCode, stderr: $err", exitCode == 137 || exitCode == 152)
        val (_, parsed) = ResourceLimitVerification.extract(err)
        assertNotNull(parsed)
        assertTrue(ResourceLimitVerification.matches(limits, parsed))
    }

    @Test fun `matches returns false when a limit silently fails to apply`() {
        val limits = ProotResourceLimits(maxOpenFiles = 64)
        val parsed = ResourceLimitVerification.Parsed(
            memoryBlocksKb = "unlimited", cpuSeconds = "unlimited",
            maxProcs = "unlimited", openFiles = "unlimited", fileBlocksKb = "unlimited"
        )
        assertFalse(ResourceLimitVerification.matches(limits, parsed))
    }

    @Test fun `matches returns true trivially when no limits were requested`() {
        assertTrue(ResourceLimitVerification.matches(ProotResourceLimits.NONE, null))
    }

    @Test fun `extract strips marker line and keeps the rest of stderr intact`() {
        val stderr = "some real error\n${ProotResourceLimits.MARKER} v=65536 t=60 u=unlimited n=64 f=102400\nanother line\n"
        val (cleaned, parsed) = ResourceLimitVerification.extract(stderr)
        assertEquals("some real error\nanother line", cleaned)
        assertEquals("65536", parsed?.memoryBlocksKb)
        assertEquals("60", parsed?.cpuSeconds)
        assertEquals("64", parsed?.openFiles)
        assertEquals("102400", parsed?.fileBlocksKb)
    }
}
