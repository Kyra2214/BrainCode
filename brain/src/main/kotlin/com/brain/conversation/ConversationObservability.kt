package com.brain.conversation

import com.brain.events.BrainEvent
import com.brain.events.EventStore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Snapshot imutável das métricas do braço conversacional. */
data class ConversationMetricsSnapshot(
    val cacheHitsByLayer: Map<String, Long>,
    val cacheMisses: Long,
    val llmCallsByHop: Map<String, Long>,
    val llmFailuresByHop: Map<String, Long>,
    val llmCostByTier: Map<String, Long>,
    val secretaryAcceptedByStage: Map<String, Long>,
    val secretaryRejectedByStage: Map<String, Long>
)

/**
 * Contadores locais e auditáveis. Não contém prompts, respostas ou credenciais.
 * Pode ser compartilhado por executores de uma sessão e exportado por um adapter.
 */
class ConversationMetrics {
    private val cacheHits = ConcurrentHashMap<String, AtomicLong>()
    private val llmCalls = ConcurrentHashMap<String, AtomicLong>()
    private val llmFailures = ConcurrentHashMap<String, AtomicLong>()
    private val llmCost = ConcurrentHashMap<String, AtomicLong>()
    private val secretaryAccepted = ConcurrentHashMap<String, AtomicLong>()
    private val secretaryRejected = ConcurrentHashMap<String, AtomicLong>()
    private val cacheMisses = AtomicLong()

    fun recordCacheHit(layer: String) { cacheHits.counter(layer).incrementAndGet() }
    fun recordCacheMiss() { cacheMisses.incrementAndGet() }
    fun recordLlmCall(hop: String, tier: String, success: Boolean) {
        llmCalls.counter(hop).incrementAndGet()
        llmCost.counter(tier).incrementAndGet()
        if (!success) llmFailures.counter(hop).incrementAndGet()
    }
    fun recordSecretary(stage: String, accepted: Boolean) {
        (if (accepted) secretaryAccepted else secretaryRejected).counter(stage).incrementAndGet()
    }

    fun snapshot(): ConversationMetricsSnapshot = ConversationMetricsSnapshot(
        cacheHitsByLayer = cacheHits.snapshot(),
        cacheMisses = cacheMisses.get(),
        llmCallsByHop = llmCalls.snapshot(),
        llmFailuresByHop = llmFailures.snapshot(),
        llmCostByTier = llmCost.snapshot(),
        secretaryAcceptedByStage = secretaryAccepted.snapshot(),
        secretaryRejectedByStage = secretaryRejected.snapshot()
    )

    private fun ConcurrentHashMap<String, AtomicLong>.counter(key: String): AtomicLong =
        computeIfAbsent(key) { AtomicLong() }

    private fun ConcurrentHashMap<String, AtomicLong>.snapshot(): Map<String, Long> =
        entries.associate { it.key to it.value.get() }.toSortedMap()
}

/** Ponte opcional entre evidências do fluxo e a trilha hash-chain persistente. */
class EventStoreConversationEvidence(
    private val store: EventStore,
    private val runId: String,
    private val sessionId: String,
    private val taskId: String
) {
    private var sequence = 0L

    @Synchronized
    fun append(evidence: String, hop: String = "conversation") {
        store.append(
            BrainEvent(
                runId = runId,
                sessionId = sessionId,
                taskId = taskId,
                type = "conversation.evidence",
                sequence = sequence++,
                payload = mapOf("evidence" to evidence.take(256), "hop" to hop.take(64)),
                idempotencyKey = "$runId:$taskId:$sequence:$evidence"
            )
        )
    }
}
