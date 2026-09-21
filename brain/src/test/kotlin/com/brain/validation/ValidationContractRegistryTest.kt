package com.brain.validation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ValidationContractRegistryTest {
    @Test
    fun capacitats_reais_recebem_contrato_e_responsavel() {
        val research = ValidationContractRegistry.contractForCapability("research.web")
        assertNotNull(research)
        assertEquals("agent.research", research.id.substringAfter("self-e2e:"))
        assertEquals("agent.research", ValidationContractRegistry.ownerForCapability("research.web"))
    }
    @Test
    fun criador_local_de_prompt_recebe_contrato_especifico() {
        val contract = ValidationContractRegistry.contractForCapability("prompt.library.generate")
        assertNotNull(contract)
        assertEquals("self-e2e:local.prompt.creator", contract.id)
    }
}