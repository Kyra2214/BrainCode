package com.sandbox.agent

import org.junit.Assert.*
import org.junit.Test

class CapabilityResolverTest {
    private val resolver = CapabilityResolver()

    @Test fun `capacidade fora do catalogo e recusada`() {
        val result = resolver.resolve("sandbox.nao_existe")
        assertTrue(result is CapabilityResolver.Resolution.Refused)
    }

    @Test fun `sandbox test repassa parametros para o script`() {
        val result = resolver.resolve("sandbox.test", listOf("-k", "meu_teste"))
        assertEquals(
            CapabilityResolver.Resolution.Comando(listOf("sandbox-test", "-k", "meu_teste")),
            result
        )
    }

    @Test fun `sandbox health nao aceita parametros`() {
        val result = resolver.resolve("sandbox.health", listOf("--verbose"))
        assertTrue(result is CapabilityResolver.Resolution.Refused)
    }

    @Test fun `sandbox health sem parametros resolve pro script fixo`() {
        val result = resolver.resolve("sandbox.health")
        assertEquals(CapabilityResolver.Resolution.Comando(listOf("sandbox-health")), result)
    }

    @Test fun `capacidade de comando generico permanece fora do catalogo`() {
        val vazio = resolver.resolve("sandbox.run")
        assertTrue(vazio is CapabilityResolver.Resolution.Refused)

        val comArgs = resolver.resolve("sandbox.run", listOf("ls", "-la"))
        assertTrue(comArgs is CapabilityResolver.Resolution.Refused)
    }

    @Test fun `catalogo customizado pode ser injetado`() {
        val custom = CapabilityResolver(mapOf(
            "minha.capacidade" to { params: List<String> -> CapabilityResolver.Resolution.Comando(listOf("echo") + params) }
        ))
        val result = custom.resolve("minha.capacidade", listOf("oi"))
        assertEquals(CapabilityResolver.Resolution.Comando(listOf("echo", "oi")), result)
        assertTrue(custom.resolve("sandbox.health") is CapabilityResolver.Resolution.Refused)
    }
}
