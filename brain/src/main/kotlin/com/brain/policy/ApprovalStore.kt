package com.brain.policy

import java.io.File
import java.time.Instant
import java.util.UUID
import org.json.JSONObject

data class ApprovalRequest(val id: String = "approval_${UUID.randomUUID()}", val runId: String, val taskId: String, val capability: String, val resource: String, val expiresAt: Instant)
enum class ApprovalStatus { PENDING, APPROVED, DENIED, CONSUMED }
data class ApprovalRecord(val request: ApprovalRequest, val status: ApprovalStatus, val decidedAt: Instant? = null)

interface ApprovalStore {
    fun create(request: ApprovalRequest): ApprovalRecord
    fun decide(id: String, approved: Boolean): ApprovalRecord?
    fun consume(id: String): ApprovalRecord?
    fun get(id: String): ApprovalRecord?
}

class FileApprovalStore(private val file: File) : ApprovalStore {
    private val lock = Any()
    private val records = linkedMapOf<String, ApprovalRecord>()
    init { synchronized(lock) { load() } }

    override fun create(request: ApprovalRequest): ApprovalRecord = synchronized(lock) {
        require(request.expiresAt.isAfter(Instant.now())) { "aprovação já expirou" }
        records[request.id] ?: ApprovalRecord(request, ApprovalStatus.PENDING).also { records[request.id] = it; persist() }
    }

    override fun decide(id: String, approved: Boolean): ApprovalRecord? = synchronized(lock) {
        val current = records[id] ?: return@synchronized null
        if (current.status != ApprovalStatus.PENDING || !current.request.expiresAt.isAfter(Instant.now())) return@synchronized null
        current.copy(status = if (approved) ApprovalStatus.APPROVED else ApprovalStatus.DENIED, decidedAt = Instant.now()).also { records[id] = it; persist() }
    }

    override fun consume(id: String): ApprovalRecord? = synchronized(lock) {
        val current = records[id] ?: return@synchronized null
        if (current.status != ApprovalStatus.APPROVED || !current.request.expiresAt.isAfter(Instant.now())) return@synchronized null
        current.copy(status = ApprovalStatus.CONSUMED).also { records[id] = it; persist() }
    }

    override fun get(id: String): ApprovalRecord? = synchronized(lock) { records[id] }

    private fun load() {
        if (!file.exists()) return
        file.readLines().forEach { line -> runCatching { fromJson(JSONObject(line)) }.getOrNull()?.let { records[it.request.id] = it } }
    }
    private fun persist() {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(records.values.joinToString("\n") { toJson(it).toString() } + if (records.isEmpty()) "" else "\n")
        check(temp.renameTo(file) || temp.copyTo(file, overwrite = true).let { temp.delete(); true })
    }
    private fun toJson(r: ApprovalRecord) = JSONObject().apply {
        put("id", r.request.id); put("runId", r.request.runId); put("taskId", r.request.taskId); put("capability", r.request.capability); put("resource", r.request.resource); put("expiresAt", r.request.expiresAt.toString()); put("status", r.status.name); put("decidedAt", r.decidedAt?.toString() ?: JSONObject.NULL)
    }
    private fun fromJson(j: JSONObject): ApprovalRecord = ApprovalRecord(
        ApprovalRequest(j.getString("id"), j.getString("runId"), j.getString("taskId"), j.getString("capability"), j.getString("resource"), Instant.parse(j.getString("expiresAt"))),
        ApprovalStatus.valueOf(j.getString("status")),
        j.opt("decidedAt")?.takeUnless { it == JSONObject.NULL }?.toString()?.takeIf { it.isNotBlank() }?.let(Instant::parse)
    )
}
