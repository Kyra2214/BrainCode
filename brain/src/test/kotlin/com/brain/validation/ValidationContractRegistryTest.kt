package com.brain.validation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ValidationContractRegistryTest {
    @Test
    fun capacitats_reais_recebem_contrato_e_responsavel() {
        val research = ValidationContractRegistry.contractForCapability("research.web")
        assertNotNull(research)
        assertEquals("self-e2e:agent.research", research.id)
        assertEquals("agent.research", ValidationContractRegistry.ownerForCapability("research.web"))
    }
    @Test
    fun capacidade_desconhecida_nao_recebe_contrato() {
        assertEquals(null, ValidationContractRegistry.contractForCapability("mystery.capability"))
        assertEquals(null, ValidationContractRegistry.ownerForCapability("mystery.capability"))
    }

    @Test
    fun criador_local_de_prompt_recebe_contrato_especifico() {
        val contract = ValidationContractRegistry.contractForCapability("prompt.library.generate")
        assertNotNull(contract)
        assertEquals("self-e2e:local.prompt.creator", contract.id)
    }

    @Test
    fun brain_analyze_recebe_contrato_leve() {
        val contract = ValidationContractRegistry.contractForCapability("brain.analyze")
        assertNotNull(contract)
        assertEquals("self-e2e:agent.requirements.analyze", contract.id)
        assertEquals("agent.requirements", ValidationContractRegistry.ownerForCapability("brain.analyze"))
    }
}
