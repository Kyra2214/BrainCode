package com.sandbox.runtime

import java.io.File
import java.util.Properties
import java.util.UUID

/** Dependency-free persistent evidence store kept outside the rootfs. */
class FileExecutionLogRepository(
    private val directory: File,
    private val maxLogs: Int = 500,
    private val maxOutputChars: Int = 256 * 1024
) : ExecutionLogRepository {
    init { directory.mkdirs() }

    @Synchronized override fun save(log: ExecutionLog) {
        directory.mkdirs()
        val p = Properties()
        p["version"] = "1"
        p["executionId"] = log.executionId
        p["sessionId"] = log.sessionId
        p["command"] = log.command.joinToString("\u001f") { sanitize(it) }
        p["workingDir"] = sanitize(log.workingDir)
        p["startedAt"] = log.startedAt.toString()
        p["finishedAt"] = log.finishedAt.toString()
        p["durationMs"] = log.durationMs.toString()
        p["exitCode"] = log.exitCode?.toString() ?: ""
        p["terminationReason"] = log.terminationReason.name
        p["timedOut"] = log.timedOut.toString()
        p["forcedKill"] = log.forcedKill.toString()
        p["stdout"] = cap(sanitize(log.stdout))
        p["stderr"] = cap(sanitize(log.stderr))
        p["sandboxState"] = log.sandboxState.name
        p["outputTruncated"] = (log.outputTruncated || log.stdout.length > maxOutputChars || log.stderr.length > maxOutputChars).toString()
        File(directory, "${safeName(log.executionId)}.properties").outputStream().use { p.store(it, "Sandbox execution evidence") }
        trim()
    }

    @Synchronized override fun get(executionId: String): ExecutionLog? = read(fileFor(executionId))

    @Synchronized override fun recent(sessionId: String?, limit: Int): List<ExecutionLog> =
        directory.listFiles { f -> f.isFile && f.extension == "properties" }
            ?.mapNotNull(::read)?.filter { sessionId == null || it.sessionId == sessionId }
            ?.sortedByDescending { maxOf(it.finishedAt, it.startedAt) }?.take(limit.coerceIn(1, 500)) ?: emptyList()

    @Synchronized override fun markInterruptedRunning(executionId: String, now: Long): ExecutionLog? {
        val old = get(executionId) ?: return null
        if (old.sandboxState != SandboxState.RUNNING || old.finishedAt != 0L) return old
        val updated = old.copy(finishedAt = now, durationMs = (now - old.startedAt).coerceAtLeast(0),
            exitCode = null, terminationReason = TerminationReason.INTERRUPTED, timedOut = false,
            forcedKill = true, sandboxState = SandboxState.READY)
        save(updated)
        return updated
    }

    @Synchronized override fun recoverRunning(sessionId: String, now: Long): List<ExecutionLog> =
        recent(sessionId, maxLogs).filter { it.sandboxState == SandboxState.RUNNING && it.finishedAt == 0L }
            .mapNotNull { markInterruptedRunning(it.executionId, now) }

    @Synchronized override fun clearHistory() {
        directory.listFiles()?.forEach { if (it.isFile) it.delete() }
    }

    private fun read(file: File): ExecutionLog? = if (!file.isFile) null else runCatching {
        val p = Properties(); file.inputStream().use { p.load(it) }
        ExecutionLog(
            executionId = p.getProperty("executionId") ?: return@runCatching null,
            sessionId = p.getProperty("sessionId") ?: "unknown",
            command = (p.getProperty("command") ?: "").split("\u001f").filter(String::isNotEmpty),
            workingDir = p.getProperty("workingDir") ?: "/home/sandbox",
            startedAt = p.getProperty("startedAt")?.toLongOrNull() ?: 0L,
            finishedAt = p.getProperty("finishedAt")?.toLongOrNull() ?: 0L,
            durationMs = p.getProperty("durationMs")?.toLongOrNull() ?: 0L,
            exitCode = p.getProperty("exitCode")?.takeIf(String::isNotEmpty)?.toIntOrNull(),
            terminationReason = runCatching { TerminationReason.valueOf(p.getProperty("terminationReason") ?: "RUNTIME_ERROR") }.getOrDefault(TerminationReason.RUNTIME_ERROR),
            timedOut = p.getProperty("timedOut")?.toBoolean() == true,
            forcedKill = p.getProperty("forcedKill")?.toBoolean() == true,
            stdout = p.getProperty("stdout") ?: "",
            stderr = p.getProperty("stderr") ?: "",
            sandboxState = runCatching { SandboxState.valueOf(p.getProperty("sandboxState") ?: "READY") }.getOrDefault(SandboxState.READY),
            outputTruncated = p.getProperty("outputTruncated")?.toBoolean() == true
        )
    }.getOrNull()

    private fun fileFor(id: String) = File(directory, "${safeName(id)}.properties")
    private fun trim() { directory.listFiles { f -> f.isFile && f.extension == "properties" }?.sortedByDescending(File::lastModified)?.drop(maxLogs.coerceAtLeast(1))?.forEach(File::delete) }
    private fun cap(value: String) = if (value.length <= maxOutputChars) value else value.take(maxOutputChars) + "\n[output truncated: limit=${maxOutputChars} chars]"
    private fun sanitize(value: String) = value.replace(Regex("(?i)(api[_-]?key|token|password|secret|authorization)\\s*[=:]\\s*\\S+"), "$1=[REDACTED]")
    private fun safeName(value: String) = value.replace(Regex("[^A-Za-z0-9._-]"), "_")
    companion object { fun newId(): String = UUID.randomUUID().toString() }
}
