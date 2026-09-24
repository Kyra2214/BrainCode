package com.brain.runtime

import com.brain.events.InMemoryEventStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeDoctorImplTest {
    @Test
    fun `diagnose incorpora o erro aos sintomas, marca nao saudavel e grava evento`() {
        val events = InMemoryEventStore()
        val diagnosis = RuntimeDoctorImpl(events).diagnose("run-1", "step-1", "executor caiu")

        assertFalse(diagnosis.healthy)
        assertTrue(diagnosis.symptoms.contains("executor caiu"))
        val diagnostic = events.replay("run-1").single { it.type == "runtime.diagnostic" }
        assertEquals("step-1", diagnostic.taskId)
        assertEquals("warning", diagnostic.payload["status"])
        assertTrue(events.verifyIntegrity())
    }

    @Test
    fun `repair sem job interrompido nao finge sucesso e registra indisponibilidade`() {
        val events = InMemoryEventStore()
        val doctor = RuntimeDoctorImpl(events)
        val diagnosis = RuntimeDiagnosis(healthy = false, symptoms = listOf("x"), recommendedRepair = "reiniciar")

        assertFalse(doctor.repair("run-2", "step-2", diagnosis))
        assertTrue(events.replay("run-2").any { it.type == "runtime.repair_unavailable" })
    }

    @Test
    fun `repair de diagnostico saudavel e no-op`() {
        val events = InMemoryEventStore()
        assertFalse(RuntimeDoctorImpl(events).repair("run-3", "step-3", RuntimeDiagnosis(healthy = true)))
        assertTrue(events.replay("run-3").isEmpty())
    }
}
