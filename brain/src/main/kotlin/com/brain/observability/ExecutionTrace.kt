package com.brain.observability

import com.brain.events.BrainEvent
import com.brain.events.EventStore
import java.time.Instant

/** Etapas observáveis do fluxo arquitetural do BrainCode. */
enum class TraceStage { TASK, PLAN, CAPABILITY, POLICY, AGENT, SANDBOX, EVIDENCE, CRITIC }

data class TraceEvent(
    val traceId: String,
    val stage: TraceStage,
    val status: String,
    val subjectId: String,
    val timestamp: Instant = Instant.now(),
    val details: Map<String, String> = emptyMap()
)

interface TraceSink { fun append(event: TraceEvent) }

class InMemoryTraceSink : TraceSink {
    private val events = mutableListOf<TraceEvent>()
    private val lock = Any()
    override fun append(event: TraceEvent) = synchronized(lock) { events += event }
    fun all(traceId: String? = null): List<TraceEvent> = synchronized(lock) {
        events.filter { traceId == null || it.traceId == traceId }.toList()
    }
}

/**
 * Sink canônico: grava o trace de execução no mesmo EventStore auditável, em vez de
 * criar um segundo armazenamento (ver ARQUITETURA_ATUAL.md seção 9). O `traceId` do
 * evento é usado como `runId` do BrainEvent para permitir correlação com o
 * `runId`/`actionId` já usado pelo ActionAuditLog — nunca inventa um id novo.
 */
class EventStoreTraceSink(
    private val events: EventStore,
    private val sessionId: String = "execution-trace"
) : TraceSink {
    override fun append(event: TraceEvent) {
        val payload = linkedMapOf(
            "traceId" to event.traceId,
            "stage" to event.stage.name,
            "status" to event.status,
            "subjectId" to event.subjectId
        )
        payload.putAll(event.details)
        val sequence = events.replay().size.toLong()
        events.append(
            BrainEvent(
                runId = event.traceId,
                sessionId = sessionId,
                taskId = event.stage.name,
                type = "ExecutionTrace",
                sequence = sequence,
                timestamp = event.timestamp,
                payload = payload
            )
        )
    }

    /** Lê de volta os TraceEvent já persistidos, opcionalmente filtrados por traceId. */
    fun all(traceId: String? = null): List<TraceEvent> = events.replay(traceId)
        .filter { it.type == "ExecutionTrace" }
        .map { event ->
            val p = event.payload
            TraceEvent(
                traceId = p["traceId"].orEmpty(),
                stage = runCatching { TraceStage.valueOf(p["stage"].orEmpty()) }.getOrDefault(TraceStage.TASK),
                status = p["status"].orEmpty(),
                subjectId = p["subjectId"].orEmpty(),
                timestamp = event.timestamp,
                details = p.filterKeys { it !in setOf("traceId", "stage", "status", "subjectId") }
            )
        }
}

/** Renderiza a visualização técnica em texto estável para logs e auditoria. */
class ExecutionTrace(private val sink: TraceSink) {
    fun record(traceId: String, stage: TraceStage, status: String, subjectId: String, details: Map<String, String> = emptyMap()) =
        sink.append(TraceEvent(traceId, stage, status, subjectId, details = details))

    fun render(events: List<TraceEvent>): String = events.sortedBy { it.timestamp }.joinToString("\n") {
        "${it.stage.name}(${it.status}):${it.subjectId}"
    }
}
