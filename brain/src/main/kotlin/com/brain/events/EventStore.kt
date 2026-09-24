package com.brain.events

import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.UUID
import java.security.MessageDigest

data class BrainEvent(
    val runId: String,
    val sessionId: String,
    val taskId: String,
    val type: String,
    val sequence: Long,
    val eventId: String = UUID.randomUUID().toString(),
    val timestamp: Instant = Instant.now(),
    val payload: Map<String, String> = emptyMap(),
    val version: Int = 1,
    val previousHash: String = "GENESIS",
    val hash: String = "",
    val idempotencyKey: String? = null
) {
    fun finalized(previous: String, sequence: Long): BrainEvent {
        val candidate = copy(sequence = sequence, previousHash = previous, hash = "")
        return candidate.copy(hash = sha256(candidate.canonical()))
    }
    fun canonical() = listOf(runId, sessionId, taskId, type, sequence, eventId, timestamp, version, previousHash, payload.toSortedMap(), idempotencyKey).joinToString("|")
}

interface EventStore {
    fun append(event: BrainEvent): BrainEvent
    fun replay(runId: String? = null): List<BrainEvent>
    fun verifyIntegrity(): Boolean
}

class InMemoryEventStore : EventStore {
    private val events = mutableListOf<BrainEvent>()
    @Synchronized override fun append(event: BrainEvent): BrainEvent {
        event.idempotencyKey?.let { key -> events.firstOrNull { it.idempotencyKey == key }?.let { return it } }
        val previous = events.lastOrNull()?.hash ?: "GENESIS"
        val stored = event.copy(payload = event.redactedPayload()).finalized(previous, events.size.toLong())
        events += stored
        return stored
    }
    @Synchronized override fun replay(runId: String?): List<BrainEvent> = events.filter { runId == null || it.runId == runId }.toList()
    @Synchronized override fun verifyIntegrity(): Boolean = verify(events)
}

class FileEventStore(private val file: File, private val maxBytes: Long = Long.MAX_VALUE) : EventStore {
    private val checkpointFile = File(file.parentFile, "${file.name}.checkpoint")
    init { file.parentFile?.mkdirs() }
    @Synchronized override fun append(event: BrainEvent): BrainEvent {
        val current = readAll()
        event.idempotencyKey?.let { key -> current.firstOrNull { it.idempotencyKey == key }?.let { return it } }
        val stored = event.copy(payload = event.redactedPayload()).finalized(current.lastOrNull()?.hash ?: "GENESIS", current.size.toLong())
        file.parentFile?.mkdirs()
        if (maxBytes != Long.MAX_VALUE && file.exists() && file.length() >= maxBytes) rotate()
        file.appendText(toJson(stored).toString() + "\n")
        checkpointFile.writeText("${stored.sequence}\t${stored.hash}\n")
        return stored
    }
    @Synchronized override fun replay(runId: String?): List<BrainEvent> = readAll().filter { runId == null || it.runId == runId }
    @Synchronized override fun verifyIntegrity(): Boolean {
        val events = readAll()
        if (!verify(events)) return false
        val last = events.lastOrNull() ?: return !checkpointFile.exists()
        val checkpoint = checkpointFile.takeIf { it.isFile }?.readText()?.trim()?.split('\t') ?: return false
        return checkpoint.size == 2 && checkpoint[0] == last.sequence.toString() && checkpoint[1] == last.hash
    }

    /** Arquiva o segmento ativo sem quebrar a cadeia lógica de hashes. */
    @Synchronized fun rotate() {
        if (!file.exists() || file.length() == 0L) return
        val archive = File(file.parentFile, "${file.name}.segment.${System.currentTimeMillis()}")
        check(file.renameTo(archive)) { "não foi possível rotacionar EventStore" }
    }

    private fun readAll(): List<BrainEvent> {
        val sources = segmentFiles() + listOf(file).filter { it.exists() }
        val valid = mutableListOf<String>()
        for (source in sources) for ((index, line) in source.readLines().withIndex()) {
            if (line.isBlank()) continue
            val parsed = runCatching { JSONObject(line) }.getOrNull()
            if (parsed == null && index == source.readLines().lastIndex && source.readBytes().lastOrNull() != '\n'.code.toByte()) break
            if (parsed == null) error("evento inválido na linha ${index + 1} de ${source.name}")
            valid += line
        }
        return valid.map { fromJson(JSONObject(it)) }
    }

    private fun segmentFiles(): List<File> = file.parentFile?.listFiles { candidate ->
        candidate.name.startsWith("${file.name}.segment.")
    }?.sortedBy { it.name } ?: emptyList()
}

private fun toJson(e: BrainEvent) = JSONObject().apply {
    put("runId", e.runId); put("sessionId", e.sessionId); put("taskId", e.taskId); put("type", e.type); put("sequence", e.sequence)
    put("eventId", e.eventId); put("timestamp", e.timestamp.toString()); put("version", e.version); put("previousHash", e.previousHash); put("hash", e.hash); put("idempotencyKey", e.idempotencyKey ?: JSONObject.NULL)
    put("payload", JSONObject(e.payload))
}
private fun fromJson(j: JSONObject) = BrainEvent(j.getString("runId"), j.getString("sessionId"), j.getString("taskId"), j.getString("type"), j.getLong("sequence"), j.getString("eventId"), Instant.parse(j.getString("timestamp")), j.optJSONObject("payload")?.let { obj -> obj.keys().asSequence().associateWith { obj.getString(it) } } ?: emptyMap(), j.optInt("version", 1), j.optString("previousHash", "GENESIS"), j.optString("hash", ""), j.opt("idempotencyKey")?.takeUnless { it == JSONObject.NULL }?.toString())
private fun verify(events: List<BrainEvent>): Boolean {
    var previous = "GENESIS"
    events.forEachIndexed { index, event -> if (event.sequence != index.toLong() || event.previousHash != previous || event.hash != sha256(event.canonical())) return false else previous = event.hash }
    return true
}
private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
fun BrainEvent.redactedPayload(): Map<String, String> = payload.mapValues { (_, value) -> Regex("(?i)(key|token|secret|password)\\s*[=:]\\s*[^,;\\s]+").replace(value, "[REDACTED]") }
