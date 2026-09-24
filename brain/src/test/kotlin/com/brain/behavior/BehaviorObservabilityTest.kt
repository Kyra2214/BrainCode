package com.brain.behavior

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.brain.events.InMemoryEventStore

class BehaviorObservabilityTest {
    @Test fun `diagnostico correlaciona eventos por run e task`() {
        val sink = InMemoryBehaviorTraceSink()
        val diagnostics = BehaviorDiagnostics(sink)
        diagnostics.record("run-1", "task-1", "policy", "ALLOW", capability = "sandbox.test", policy = "decision-1")
        diagnostics.record("run-1", "task-1", "verification", "PASSED", result = "ok")
        diagnostics.record("run-2", "task-2", "policy", "DENY")
        assertEquals(2, sink.list("run-1").size)
        assertEquals("verification", sink.list("run-1").last().stage)
    }

    @Test fun `diagnostico redige secrets e credential refs`() {
        val sink = InMemoryBehaviorTraceSink()
        BehaviorDiagnostics(sink).record("run", "task", "execution", "FAILED", "api_key=abc123 credential:account-a password=secret")
        val detail = sink.list().single().detail
        assertFalse(detail.contains("abc123"))
        assertFalse(detail.contains("account-a"))
        assertTrue(detail.contains("[REDACTED]"))
    }

    @Test fun `event store e o sink canonico de behavior trace`() {
        val events = InMemoryEventStore()
        val diagnostics = BehaviorDiagnostics(EventStoreBehaviorTraceSink(events))
        diagnostics.record("run", "task", "readiness", "READY", readiness = "READY")
        assertEquals(1, events.replay("run").count { it.type == "BehaviorTrace" })
        assertEquals("READY", EventStoreBehaviorTraceSink(events).list("run").single().readiness)
        assertTrue(events.verifyIntegrity())
    }
}
