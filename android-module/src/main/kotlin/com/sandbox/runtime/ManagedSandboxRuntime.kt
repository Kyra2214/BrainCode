package com.sandbox.runtime

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Lifecycle-aware execution layer for agent workloads. */
class ManagedSandboxRuntime(
    private val launcher: SandboxProcessLauncher,
    private val repository: ExecutionLogRepository,
    private val sessionId: String = FileExecutionLogRepository.newId(),
    private val maxOutputChars: Int = 256 * 1024,
    private val events: (RuntimeEvent) -> Unit = {},
    private val runtimeEventRepository: RuntimeEventRepository? = null
) : AutoCloseable {
    private val stateRef = AtomicReference(SandboxState.NEW)
    private val active = AtomicReference<ActiveExecution?>(null)
    private val lock = Any()

    val state: SandboxState get() = stateRef.get()
    val currentExecutionId: String? get() = active.get()?.id

    init {
        repository.recoverRunning(sessionId)
        stateRef.set(SandboxState.READY)
    }

    /**
     * Network is enabled by default because the mobile sandbox is intended to
     * talk to GitHub, AI APIs and download missing dependencies/LLMs. Callers
     * that explicitly need a network namespace may still pass false; the
     * launcher treats that isolation as best-effort on Android.
     */
    fun execute(command: List<String>, timeoutSeconds: Long = 120, workingDir: String = "/home/sandbox", networkAllowed: Boolean = true, onOutput: (line: String, stderr: Boolean) -> Unit = { _, _ -> }): ExecutionLog {
        require(command.isNotEmpty()) { "command não pode ser vazio" }
        require(timeoutSeconds > 0) { "timeoutSeconds deve ser > 0" }
        val id = FileExecutionLogRepository.newId()
        val started = System.currentTimeMillis()
        synchronized(lock) {
            check(stateRef.get() == SandboxState.READY) { "Sandbox não está pronto: ${stateRef.get()}" }
            check(active.get() == null) { "Já existe uma execução em andamento" }
            try {
                val process = launcher.launch(command, workingDir, networkAllowed)
                val a = ActiveExecution(id, process)
                active.set(a)
                stateRef.set(SandboxState.RUNNING)
                startProcessWatchdog(a)
                val marker = ExecutionLog(id, sessionId, command, workingDir, started, 0L, 0L, null,
                    TerminationReason.RUNTIME_ERROR, false, false, "", "", SandboxState.RUNNING)
                runCatching { repository.save(marker) }.onFailure { emit(RuntimeEventType.PERSISTENCE_ERROR, id, it.message) }
            } catch (t: Throwable) {
                stateRef.set(SandboxState.FAILED)
                emit(RuntimeEventType.PROOT_START_FAILED, id, t.message)
                val now = System.currentTimeMillis()
                val failed = ExecutionLog(id, sessionId, command, workingDir, started, now, now - started,
                    null, TerminationReason.START_FAILED, false, false, "", t.message ?: t.javaClass.simpleName, SandboxState.FAILED)
                runCatching { repository.save(failed) }.onFailure { emit(RuntimeEventType.PERSISTENCE_ERROR, id, it.message) }
                stateRef.set(SandboxState.READY)
                return failed
            }
        }
        val a = active.get()!!
        return try {
            finish(a, command, workingDir, started, timeoutSeconds, onOutput)
        } catch (t: Throwable) {
            // finish() falhando aqui deixava o runtime preso em RUNNING para sempre
            // (active nunca era limpo, stateRef nunca voltava a READY), fazendo todo
            // comando seguinte falhar de imediato no check() acima. Precisamos
            // sempre liberar o estado e devolver um log explicando o erro real, em
            // vez de deixar a exceção subir e ser engolida como "Comando falhou ao
            // executar." sem detalhe nenhum.
            emit(RuntimeEventType.RUNTIME_ERROR_UNHANDLED_FINISH, a.id, t.message)
            runCatching { stopProcess(a.process) }
            val now = System.currentTimeMillis()
            val failed = ExecutionLog(a.id, sessionId, command, workingDir, started, now, now - started,
                null, TerminationReason.RUNTIME_ERROR, false, false, "", t.message ?: t.javaClass.simpleName, SandboxState.READY)
            runCatching { repository.save(failed) }.onFailure { emit(RuntimeEventType.PERSISTENCE_ERROR, a.id, it.message) }
            failed
        } finally {
            active.compareAndSet(a, null)
            stateRef.set(SandboxState.READY)
        }
    }

    fun cancel(): Boolean = terminateActive(TerminationReason.CANCELLED, RuntimeEventType.PROCESS_CANCELLED)

    fun shutdown(): Boolean {
        synchronized(lock) {
            if (stateRef.get() == SandboxState.CLOSED) return false
            stateRef.set(SandboxState.STOPPING)
        }
        val hadProcess = terminateActive(TerminationReason.SHUTDOWN, null)
        waitForFinish()
        emit(RuntimeEventType.RUNTIME_CLOSE, null, if (hadProcess) "active execution stopped" else null)
        stateRef.set(SandboxState.CLOSED)
        return hadProcess
    }

    fun reopen(): List<ExecutionLog> {
        synchronized(lock) {
            check(active.get() == null) { "Não é seguro reabrir durante execução" }
            stateRef.set(SandboxState.READY)
        }
        return repository.recent(sessionId, 50)
    }

    fun reset(deleteRuntimeFiles: () -> Unit = {}) {
        terminateActive(TerminationReason.SHUTDOWN, null)
        waitForFinish()
        try {
            deleteRuntimeFiles()
            emit(RuntimeEventType.RUNTIME_RESET, null, "runtime files reset; execution history preserved")
            stateRef.set(SandboxState.READY)
        } catch (t: Throwable) {
            stateRef.set(SandboxState.FAILED)
            emit(RuntimeEventType.PERSISTENCE_ERROR, null, t.message)
            throw t
        }
    }

    fun getExecution(executionId: String): ExecutionLog? = repository.get(executionId)
    fun getRecentExecutions(limit: Int = 50): List<ExecutionLog> = repository.recent(sessionId, limit)
    fun getExecutionOutput(executionId: String): Pair<String, String>? = repository.get(executionId)?.let { it.stdout to it.stderr }
    fun getSessionLog(): SessionLog {
        val logs = repository.recent(sessionId, 500)
        return SessionLog(sessionId, logs.minOfOrNull { it.startedAt } ?: System.currentTimeMillis(),
            logs.maxOfOrNull { maxOf(it.finishedAt, it.startedAt) } ?: 0L, logs.size)
    }
    fun getRuntimeEvents(limit: Int = 200): List<RuntimeEvent> = runtimeEventRepository?.recent(limit) ?: emptyList()
    fun recoverInterrupted(executionId: String): ExecutionLog? = repository.markInterruptedRunning(executionId)

    override fun close() { shutdown() }

    private fun finish(a: ActiveExecution, command: List<String>, workingDir: String, started: Long, timeoutSeconds: Long, onOutput: (String, Boolean) -> Unit): ExecutionLog {
        val stdout = StringCollector(maxOutputChars) { emit(RuntimeEventType.STREAM_READ_ERROR, a.id, it) }
        val stderr = StringCollector(maxOutputChars) { emit(RuntimeEventType.STREAM_READ_ERROR, a.id, it) }
        val latch = CountDownLatch(2)
        val outThread = stream(a.process.inputStream, stdout, latch, false, onOutput)
        val errThread = stream(a.process.errorStream, stderr, latch, true, onOutput)
        var forcedKill = a.forcedKill.get()
        try {
            if (!a.process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                a.reason.compareAndSet(null, TerminationReason.TIMEOUT)
                emit(RuntimeEventType.PROCESS_TIMEOUT, a.id, "timeout=${timeoutSeconds}s")
                forcedKill = stopProcess(a.process) || forcedKill
            }
        } catch (_: InterruptedException) {
            a.reason.compareAndSet(null, TerminationReason.INTERRUPTED)
            forcedKill = stopProcess(a.process) || forcedKill
            Thread.currentThread().interrupt()
        } finally {
            if (a.reason.get() == null) a.reason.set(TerminationReason.PROCESS_EXIT)
            if (a.reason.get() != TerminationReason.PROCESS_EXIT && a.process.isAlive) forcedKill = stopProcess(a.process) || forcedKill
            runCatching { latch.await(2, TimeUnit.SECONDS) }
            outThread.joinQuietly(); errThread.joinQuietly()
        }
        val finished = System.currentTimeMillis()
        val reason = a.reason.get() ?: TerminationReason.RUNTIME_ERROR
        val exitCode = if (!a.process.isAlive) runCatching { a.process.exitValue() }.getOrNull() else null
        val (cleanedStderr, verifiedLimits) = verifyResourceLimits(stderr.value())
        if (verifiedLimits == false) emit(RuntimeEventType.RESOURCE_LIMIT_UNVERIFIED, a.id, "requested=${launcher.resourceLimits}")
        val log = ExecutionLog(a.id, sessionId, command, workingDir, started, finished, finished - started,
            if (reason == TerminationReason.PROCESS_EXIT) exitCode else null,
            reason, reason == TerminationReason.TIMEOUT, forcedKill || a.forcedKill.get(),
            stdout.value(), cleanedStderr, SandboxState.READY)
        runCatching { repository.save(log) }.onFailure { emit(RuntimeEventType.PERSISTENCE_ERROR, a.id, it.message) }
        active.compareAndSet(a, null)
        stateRef.set(SandboxState.READY)
        return log
    }

    private fun terminateActive(reason: TerminationReason, event: RuntimeEventType?): Boolean {
        val a = active.get() ?: return false
        a.reason.compareAndSet(null, reason)
        synchronized(lock) { if (stateRef.get() != SandboxState.CLOSED) stateRef.set(SandboxState.STOPPING) }
        event?.let { emit(it, a.id, reason.name) }
        if (!a.process.isAlive) return true
        val forced = stopProcess(a.process)
        if (forced) { a.forcedKill.set(true); emit(RuntimeEventType.PROCESS_FORCED_KILL, a.id, reason.name) }
        return true
    }

    private fun waitForFinish() {
        repeat(30) {
            if (active.get() == null) return
            try { Thread.sleep(100) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); return }
        }
    }

    private fun startProcessWatchdog(a: ActiveExecution) {
        val limit = launcher.resourceLimits.maxProcesses ?: return
        Thread {
            while (active.get() === a && a.process.isAlive) {
                val pid = processPid(a.process)
                if (pid != null && ProcessTreeTerminator.processCount(pid) > limit) {
                    a.reason.compareAndSet(null, TerminationReason.RESOURCE_LIMIT)
                    emit(RuntimeEventType.PROCESS_FORCED_KILL, a.id, "maxProcesses=$limit")
                    stopProcess(a.process)
                    return@Thread
                }
                try { Thread.sleep(100) } catch (_: InterruptedException) { return@Thread }
            }
        }.apply { isDaemon = true; name = "sandbox-process-watchdog-${a.id}"; start() }
    }

    private fun verifyResourceLimits(rawStderr: String): Pair<String, Boolean?> {
        val limits = launcher.resourceLimits
        if (!limits.hasLimits()) return rawStderr to null
        val (cleaned, parsed) = ResourceLimitVerification.extract(rawStderr)
        return cleaned to ResourceLimitVerification.matches(limits, parsed)
    }

    private fun stopProcess(process: Process): Boolean {
        val pid = processPid(process)
        if (pid != null) ProcessTreeTerminator.terminateTree(pid, "TERM", launcher.processGroupManaged)
        process.destroy()
        if (runCatching { process.waitFor(250, TimeUnit.MILLISECONDS) }.getOrDefault(false)) return false
        if (pid != null) ProcessTreeTerminator.terminateTree(pid, "KILL", launcher.processGroupManaged)
        process.destroyForcibly()
        runCatching { process.waitFor(1, TimeUnit.SECONDS) }
        return true
    }

    private fun processPid(process: Process): Long? = runCatching {
        val method = process.javaClass.methods.firstOrNull { it.name == "pid" && it.parameterTypes.isEmpty() }
            ?: return@runCatching null
        (method.invoke(process) as? Number)?.toLong()
    }.getOrNull()

    private fun stream(input: java.io.InputStream, collector: StringCollector, latch: CountDownLatch, stderr: Boolean, onOutput: (String, Boolean) -> Unit): Thread = Thread {
        try { input.bufferedReader().forEachLine { collector.append(it); runCatching { onOutput(it, stderr) } } }
        catch (t: Throwable) { collector.error(t.message ?: t.javaClass.simpleName) }
        finally { runCatching { input.close() }; latch.countDown() }
    }.apply { isDaemon = true; start() }

    private fun emit(type: RuntimeEventType, id: String?, detail: String?) {
        val event = RuntimeEvent(System.currentTimeMillis(), type, id, detail)
        runCatching { runtimeEventRepository?.append(event) }
        runCatching { events(event) }
    }

    private data class ActiveExecution(
        val id: String,
        val process: Process,
        val reason: AtomicReference<TerminationReason?> = AtomicReference(null),
        val forcedKill: java.util.concurrent.atomic.AtomicBoolean = java.util.concurrent.atomic.AtomicBoolean(false)
    )

    private class StringCollector(private val max: Int, private val onError: (String) -> Unit) {
        private val sb = StringBuilder()
        @Synchronized fun append(line: String) {
            if (sb.length >= max) return
            val remaining = max - sb.length
            if (line.length + 1 <= remaining) sb.append(line).append('\n') else sb.append(line.take((remaining - 1).coerceAtLeast(0)))
        }
        fun error(message: String) = onError(message)
        @Synchronized fun value(): String = if (sb.length >= max) sb.toString() + "\n[output truncated]" else sb.toString()
    }

    private fun Thread.joinQuietly() = runCatching { join(2_000) }
}

private object ProcessTreeTerminator {
    fun processCount(root: Long): Int = descendantPids(root).size + 1

    fun terminateTree(pid: Long, signal: String, processGroupManaged: Boolean) {
        if (pid <= 0) return
        val kill = listOf("/system/bin/kill", "/usr/bin/kill", "/bin/kill").firstOrNull { java.io.File(it).canExecute() } ?: return
        if (processGroupManaged) {
            runCatching { ProcessBuilder(kill, "-$signal", "--", "-$pid").start().waitFor(1, TimeUnit.SECONDS) }
        } else {
            descendantPids(pid).asReversed().forEach { child ->
                runCatching { ProcessBuilder(kill, "-$signal", child.toString()).start().waitFor(1, TimeUnit.SECONDS) }
            }
            runCatching { ProcessBuilder(kill, "-$signal", pid.toString()).start().waitFor(1, TimeUnit.SECONDS) }
        }
    }

    private fun descendantPids(root: Long): List<Long> {
        val parents = mutableMapOf<Long, Long>()
        val proc = File("/proc")
        proc.listFiles()?.forEach { entry ->
            val pid = entry.name.toLongOrNull() ?: return@forEach
            val stat = File(entry, "stat").readTextOrNull() ?: return@forEach
            val close = stat.lastIndexOf(')')
            if (close < 0) return@forEach
            val fields = stat.substring(close + 2).trim().split(Regex("\\s+"))
            val parent = fields.getOrNull(1)?.toLongOrNull() ?: return@forEach
            parents[pid] = parent
        }
        val found = mutableSetOf<Long>()
        var frontier = setOf(root)
        while (frontier.isNotEmpty()) {
            val next = parents.filterValues { it in frontier }.keys - found - root
            if (next.isEmpty()) break
            found += next
            frontier = next
        }
        return found.toList()
    }

    private fun File.readTextOrNull(): String? = runCatching { readText() }.getOrNull()
}
