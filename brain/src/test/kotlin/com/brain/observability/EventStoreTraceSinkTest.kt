package com.brain.observability

import com.brain.events.InMemoryEventStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EventStoreTraceSinkTest {
    @Test fun `append grava TraceEvent no EventStore com o traceId correto`() {
        val events = InMemoryEventStore()
        val sink = EventStoreTraceSink(events)

        sink.append(TraceEvent("run-1", TraceStage.TASK, "created", "run-1"))
        sink.append(TraceEvent("run-1", TraceStage.CAPABILITY, "requested", "sandbox.health"))
        sink.append(TraceEvent("run-2", TraceStage.TASK, "created", "run-2"))

        val stored = events.replay()
        assertEquals(3, stored.size)
        assertTrue(stored.all { it.type == "ExecutionTrace" })
        assertEquals(listOf("run-1", "run-1", "run-2"), stored.map { it.runId })
        assertEquals("TASK", stored[0].payload["stage"])
        assertEquals("sandbox.health", stored[1].payload["subjectId"])
    }

    @Test fun `all filtra por traceId e reconstroi o TraceEvent`() {
        val events = InMemoryEventStore()
        val sink = EventStoreTraceSink(events)
        sink.append(TraceEvent("run-1", TraceStage.POLICY, "ALLOW", "decision-1"))
        sink.append(TraceEvent("run-2", TraceStage.POLICY, "DENY", "decision-2"))

        val onlyRun1 = sink.all("run-1")

        assertEquals(1, onlyRun1.size)
        assertEquals(TraceStage.POLICY, onlyRun1.single().stage)
        assertEquals("ALLOW", onlyRun1.single().status)
        assertEquals("decision-1", onlyRun1.single().subjectId)
    }
}
