package com.brain.job

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.UUID

/** Estado persistível de uma operação longa. */
enum class JobStatus {
    CREATED,
    QUEUED,
    RUNNING,
    WAITING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    RETRYING
}

data class JobRecord(
    val jobId: String,
    val runId: String,
    val taskId: String,
    val status: JobStatus,
    val createdAt: String,
    val updatedAt: String,
    val attempts: Int = 0,
    val payload: Map<String, String> = emptyMap(),
    val evidence: List<String> = emptyList(),
    val error: String? = null
) {
    init {
        require(jobId.isNotBlank()) { "jobId é obrigatório" }
        require(runId.isNotBlank()) { "runId é obrigatório" }
        require(taskId.isNotBlank()) { "taskId é obrigatório" }
        require(attempts >= 0) { "attempts não pode ser negativo" }
    }
}

/**
 * Store persistente de jobs. A memória em processo é apenas índice de trabalho;
 * cada mutação grava um snapshot atômico no arquivo fornecido.
 */
class JobStore(private val file: File) {
    private val jobs = linkedMapOf<String, JobRecord>()
    private val lock = Any()

    init { synchronized(lock) { load() } }

    fun create(
        jobId: String = "job_${UUID.randomUUID()}",
        runId: String,
        taskId: String,
        payload: Map<String, String> = emptyMap()
    ): JobRecord = synchronized(lock) {
        require(jobId !in jobs) { "job já existe: $jobId" }
        val now = Instant.now().toString()
        val record = JobRecord(jobId, runId, taskId, JobStatus.CREATED, now, now, payload = payload)
        jobs[jobId] = record
        persist()
        record
    }

    fun get(jobId: String): JobRecord? = synchronized(lock) { jobs[jobId] }

    fun all(): List<JobRecord> = synchronized(lock) { jobs.values.toList() }

    fun transition(
        jobId: String,
        status: JobStatus,
        error: String? = null,
        incrementAttempt: Boolean = status == JobStatus.RUNNING || status == JobStatus.RETRYING
    ): JobRecord = synchronized(lock) {
        val current = jobs[jobId] ?: throw NoSuchElementException("job não encontrado: $jobId")
        require(status in allowedTransitions.getValue(current.status)) {
            "transição inválida: ${current.status} → $status"
        }
        val updated = current.copy(
            status = status,
            updatedAt = Instant.now().toString(),
            attempts = current.attempts + if (incrementAttempt) 1 else 0,
            error = error
        )
        jobs[jobId] = updated
        persist()
        updated
    }

    fun appendEvidence(jobId: String, vararg items: String): JobRecord = synchronized(lock) {
        val current = jobs[jobId] ?: throw NoSuchElementException("job não encontrado: $jobId")
        require(items.none { it.isBlank() }) { "evidência não pode ser vazia" }
        val updated = current.copy(
            updatedAt = Instant.now().toString(),
            evidence = (current.evidence + items).distinct()
        )
        jobs[jobId] = updated
        persist()
        updated
    }

    private fun persist() {
        file.parentFile?.mkdirs()
        val items = JSONArray()
        jobs.values.forEach { record ->
            items.put(
                JSONObject()
                    .put("jobId", record.jobId)
                    .put("runId", record.runId)
                    .put("taskId", record.taskId)
                    .put("status", record.status.name)
                    .put("createdAt", record.createdAt)
                    .put("updatedAt", record.updatedAt)
                    .put("attempts", record.attempts)
                    .put("payload", JSONObject(record.payload))
                    .put("evidence", JSONArray(record.evidence))
                    .put("error", record.error ?: JSONObject.NULL)
            )
        }
        val temporary = File(file.parentFile ?: File("."), ".${file.name}.tmp-${UUID.randomUUID()}")
        temporary.writeText(JSONObject().put("version", 1).put("jobs", items).toString())
        check(temporary.renameTo(file)) { "não foi possível persistir JobStore" }
    }

    private fun load() {
        if (!file.isFile) return
        runCatching {
            val items = JSONObject(file.readText()).optJSONArray("jobs") ?: JSONArray()
            for (index in 0 until items.length()) {
                val json = items.getJSONObject(index)
                val payloadJson = json.optJSONObject("payload") ?: JSONObject()
                val payload = payloadJson.keys().asSequence().associateWith { key -> payloadJson.getString(key) }
                val evidenceJson = json.optJSONArray("evidence") ?: JSONArray()
                val evidence = (0 until evidenceJson.length()).map(evidenceJson::getString)
                val record = JobRecord(
                    jobId = json.getString("jobId"),
                    runId = json.getString("runId"),
                    taskId = json.getString("taskId"),
                    status = JobStatus.valueOf(json.getString("status")),
                    createdAt = json.getString("createdAt"),
                    updatedAt = json.getString("updatedAt"),
                    attempts = json.optInt("attempts", 0),
                    payload = payload,
                    evidence = evidence,
                    error = json.optString("error").takeUnless { it == "null" }
                )
                jobs[record.jobId] = record
            }
        }
    }

    companion object {
        private val allowedTransitions = mapOf(
            JobStatus.CREATED to setOf(JobStatus.QUEUED, JobStatus.CANCELLED, JobStatus.FAILED),
            JobStatus.QUEUED to setOf(JobStatus.RUNNING, JobStatus.CANCELLED, JobStatus.RETRYING),
            JobStatus.RUNNING to setOf(JobStatus.WAITING, JobStatus.SUCCEEDED, JobStatus.FAILED, JobStatus.CANCELLED, JobStatus.RETRYING),
            JobStatus.WAITING to setOf(JobStatus.QUEUED, JobStatus.RUNNING, JobStatus.CANCELLED),
            JobStatus.RETRYING to setOf(JobStatus.QUEUED, JobStatus.RUNNING, JobStatus.FAILED, JobStatus.CANCELLED),
            JobStatus.SUCCEEDED to emptySet(),
            JobStatus.FAILED to emptySet(),
            JobStatus.CANCELLED to emptySet()
        )
    }
}
