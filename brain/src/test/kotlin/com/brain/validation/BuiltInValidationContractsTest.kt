package com.brain.validation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BuiltInValidationContractsTest {
    @Test
    fun `todo especialista declarado possui contrato Self-E2E`() {
        val contracts = BuiltInValidationContracts.all()
        assertEquals(14, contracts.size)
        assertTrue(contracts.keys.contains("agent.documentation"))
        contracts.values.forEach {
            assertEquals(ValidationLevel.AGENT, it.level)
            assertNotNull(it.checks.singleOrNull { check -> check.id == "result-or-evidence" })
        }
    }
}
