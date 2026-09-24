package com.brain.conversation

import com.brain.events.InMemoryEventStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConversationObservabilityTest {
    @Test
    fun `snapshot agrega cache llm custo e decisoes por estagio`() {
        val metrics = ConversationMetrics()
        metrics.recordCacheHit("layer1")
        metrics.recordCacheHit("layer1")
        metrics.recordCacheHit("layer2")
        metrics.recordCacheMiss()
        metrics.recordLlmCall("interpreter", "free", success = true)
        metrics.recordLlmCall("reviewer", "free", success = false)
        metrics.recordSecretary("content", accepted = true)
        metrics.recordSecretary("form", accepted = false)

        val snapshot = metrics.snapshot()

        assertEquals(mapOf("layer1" to 2L, "layer2" to 1L), snapshot.cacheHitsByLayer)
        assertEquals(1L, snapshot.cacheMisses)
        assertEquals(mapOf("interpreter" to 1L, "reviewer" to 1L), snapshot.llmCallsByHop)
        assertEquals(mapOf("reviewer" to 1L), snapshot.llmFailuresByHop)
        assertEquals(mapOf("free" to 2L), snapshot.llmCostByTier)
        assertEquals(mapOf("content" to 1L), snapshot.secretaryAcceptedByStage)
        assertEquals(mapOf("form" to 1L), snapshot.secretaryRejectedByStage)
    }

    @Test
    fun `metricas vazias nao inventam atividade`() {
        val snapshot = ConversationMetrics().snapshot()
        assertEquals(emptyMap(), snapshot.cacheHitsByLayer)
        assertEquals(0L, snapshot.cacheMisses)
        assertEquals(emptyMap(), snapshot.llmCallsByHop)
        assertEquals(emptyMap(), snapshot.secretaryRejectedByStage)
    }

    @Test
    fun `evidencia conversacional e persistida no event store`() {
        val store = InMemoryEventStore()
        val sink = EventStoreConversationEvidence(store, "run", "session", "task")
        sink.append("chat:cache:hit:layer1", "recall")
        sink.append("chat:llm:interpreter", "interpreter")

        assertEquals(listOf("chat:cache:hit:layer1", "chat:llm:interpreter"), store.replay().map { it.payload["evidence"] })
        assertTrue(store.verifyIntegrity())
    }
}
