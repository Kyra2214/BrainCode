package com.brain.behavior

import com.brain.events.BrainEvent
import com.brain.events.EventStore
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

/** Sink canônico: mantém o trace de comportamento no mesmo EventStore auditável. */
class EventStoreBehaviorTraceSink(
    private val events: EventStore,
    private val sessionId: String = "behavior-observability"
) : BehaviorTraceSink {
    override fun append(trace: BehaviorTrace) {
        val payload = linkedMapOf(
            "traceId" to trace.traceId,
            "stage" to trace.stage,
            "status" to trace.status,
            "detail" to trace.detail,
            "attempt" to (trace.attempt?.toString() ?: ""),
            "result" to (trace.result ?: ""),
            "critic" to (trace.critic ?: ""),
            "revision" to (trace.revision ?: ""),
            "readiness" to (trace.readiness ?: ""),
            "learning" to (trace.learning ?: "")
        )
        trace.capability?.let { payload["capability"] = it }
        trace.policy?.let { payload["policy"] = it }
        val sequence = events.replay().size.toLong()
        events.append(BrainEvent(trace.runId, sessionId, trace.taskId, "BehaviorTrace", sequence, payload = payload))
    }

    override fun list(runId: String?): List<BehaviorTrace> = events.replay(runId)
        .filter { it.type == "BehaviorTrace" }
        .map { event ->
            val p = event.payload
            BehaviorTrace(
                traceId = p["traceId"].orEmpty(), runId = event.runId, taskId = event.taskId,
                stage = p["stage"].orEmpty(), status = p["status"].orEmpty(), detail = p["detail"].orEmpty(),
                capability = p["capability"], policy = p["policy"], attempt = p["attempt"]?.toIntOrNull(),
                result = p["result"]?.ifBlank { null }, critic = p["critic"]?.ifBlank { null },
                revision = p["revision"]?.ifBlank { null }, readiness = p["readiness"]?.ifBlank { null },
                learning = p["learning"]?.ifBlank { null }, recordedAt = event.timestamp
            )
        }
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
