package com.sandbox.runtime

import java.io.File
import java.util.Properties

interface RuntimeEventRepository {
    fun append(event: RuntimeEvent)
    fun recent(limit: Int = 200): List<RuntimeEvent>
    fun clear()
}

/** Persistent runtime diagnostics, separate from command stdout/stderr. */
class FileRuntimeEventStore(private val directory: File, private val maxEvents: Int = 1000) : RuntimeEventRepository {
    init { directory.mkdirs() }

    @Synchronized override fun append(event: RuntimeEvent) {
        directory.mkdirs()
        val p = Properties()
        p["timestamp"] = event.timestamp.toString()
        p["type"] = event.type.name
        p["executionId"] = event.executionId ?: ""
        p["detail"] = sanitize(event.detail ?: "")
        File(directory, "${event.timestamp}-${System.nanoTime()}.properties").outputStream().use { p.store(it, "Sandbox runtime event") }
        trim()
    }

    @Synchronized override fun recent(limit: Int): List<RuntimeEvent> =
        directory.listFiles { f -> f.isFile && f.extension == "properties" }
            ?.mapNotNull(::read)?.sortedByDescending { it.timestamp }?.take(limit.coerceIn(1, maxEvents)) ?: emptyList()

    @Synchronized override fun clear() { directory.listFiles()?.forEach { if (it.isFile) it.delete() } }

    private fun read(file: File): RuntimeEvent? = runCatching {
        val p = Properties(); file.inputStream().use { p.load(it) }
        RuntimeEvent(
            p.getProperty("timestamp")?.toLongOrNull() ?: return@runCatching null,
            runCatching { RuntimeEventType.valueOf(p.getProperty("type") ?: "PERSISTENCE_ERROR") }.getOrDefault(RuntimeEventType.PERSISTENCE_ERROR),
            p.getProperty("executionId")?.takeIf(String::isNotEmpty),
            p.getProperty("detail")
        )
    }.getOrNull()

    private fun trim() {
        directory.listFiles { f -> f.isFile && f.extension == "properties" }
            ?.sortedByDescending(File::lastModified)?.drop(maxEvents.coerceAtLeast(1))?.forEach(File::delete)
    }

    private fun sanitize(value: String) = value.replace(Regex("(?i)(api[_-]?key|token|password|secret|authorization)\\s*[=:]\\s*\\S+"), "$1=[REDACTED]")
}
