package com.brain.behavior

import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

data class BehaviorTrace(
    val traceId: String,
    val runId: String,
    val taskId: String,
    val stage: String,
    val status: String,
    val detail: String = "",
    val capability: String? = null,
    val policy: String? = null,
    val attempt: Int? = null,
    val result: String? = null,
    val critic: String? = null,
    val revision: String? = null,
    val readiness: String? = null,
    val learning: String? = null,
    val recordedAt: Instant = Instant.now()
) {
    init {
        require(traceId.isNotBlank() && runId.isNotBlank() && taskId.isNotBlank() && stage.isNotBlank()) {
            "trace precisa de ids e estágio"
        }
    }
}

interface BehaviorTraceSink {
    fun append(trace: BehaviorTrace)
    fun list(runId: String? = null): List<BehaviorTrace>
}

class InMemoryBehaviorTraceSink : BehaviorTraceSink {
    private val traces = CopyOnWriteArrayList<BehaviorTrace>()
    override fun append(trace: BehaviorTrace) { traces += trace.copy(detail = redact(trace.detail), result = trace.result?.let(::redact)) }
    override fun list(runId: String?): List<BehaviorTrace> = traces.filter { runId == null || it.runId == runId }
    private fun redact(value: String): String = value
        .replace(Regex("(?i)(bearer\\s+|api[_-]?key|token|password|secret)[=: ]+[^,; ]+"), "[REDACTED]")
        .replace(Regex("(?i)credential:[A-Za-z0-9._-]+"), "credential:[REDACTED]")
        .take(2000)
}

class BehaviorDiagnostics(private val sink: BehaviorTraceSink) {
    fun record(
        runId: String,
        taskId: String,
        stage: String,
        status: String,
        detail: String = "",
        capability: String? = null,
        policy: String? = null,
        attempt: Int? = null,
        result: String? = null,
        critic: String? = null,
        revision: String? = null,
        readiness: String? = null,
        learning: String? = null
    ) = sink.append(BehaviorTrace("$runId:$taskId:${sink.list(runId).size}", runId, taskId, stage, status, detail, capability, policy, attempt, result, critic, revision, readiness, learning))
}
