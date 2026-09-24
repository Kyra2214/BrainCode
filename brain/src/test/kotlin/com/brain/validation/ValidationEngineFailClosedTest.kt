package com.brain.validation

import kotlin.test.Test
import kotlin.test.assertEquals

class ValidationEngineFailClosedTest {
    @Test
    fun capability_sem_contrato_nao_pode_passar_self_e2e() {
        val result = ValidationEngine().selfAgent(
            ValidationSubject(
                capability = "mystery.capability",
                result = "resultado aparentemente válido"
            )
        )
        assertEquals(ValidationStatus.FAIL, result.status)
        assertEquals(true, result.failedChecks.contains("contract.missing"))
        assertEquals(true, result.failedChecks.contains("agent.unavailable"))
    }
}
