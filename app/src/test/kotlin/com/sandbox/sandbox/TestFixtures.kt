package com.sandbox.sandbox

import com.sandbox.runtime.ExecutionLog
import com.sandbox.runtime.SandboxState
import com.sandbox.runtime.TerminationReason
import java.util.concurrent.atomic.AtomicInteger

/** Executor falso usado nos testes: nunca toca em um sandbox de verdade. */
class FakeExecutor(private val succeed: Boolean = true) : SandboxCommandExecutor {
    val calls = mutableListOf<List<String>>()
    private val counter = AtomicInteger()

    override fun execute(command: List<String>, timeoutSeconds: Long, workingDir: String): ExecutionLog {
        calls += command
        val now = System.currentTimeMillis()
        return ExecutionLog(
            executionId = "exec-${counter.incrementAndGet()}",
            sessionId = "session-test",
            command = command,
            workingDir = workingDir,
            startedAt = now,
            finishedAt = now,
            durationMs = 0,
            exitCode = if (succeed) 0 else 1,
            terminationReason = TerminationReason.PROCESS_EXIT,
            timedOut = false,
            forcedKill = false,
            stdout = "",
            stderr = if (succeed) "" else "erro simulado",
            sandboxState = SandboxState.READY
        )
    }
}

fun tempComponentsFile(): java.io.File = java.io.File.createTempFile("components", ".json").apply { deleteOnExit() }
fun tempTsvFile(): java.io.File = java.io.File.createTempFile("components", ".tsv").apply { deleteOnExit() }
