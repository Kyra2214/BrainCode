package com.sandbox.app

import com.brain.capability.CapabilityAvailability
import com.sandbox.sandbox.BuiltInCatalog
import com.sandbox.sandbox.ComponentKind
import com.sandbox.sandbox.InstallationState
import com.sandbox.sandbox.SandboxComponent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginCatalogCapabilityProviderTest {
    @Test
    fun `converte todos os componentes built in em capabilities descobriveis`() {
        val definitions = PluginCatalogCapabilityProvider().capabilities().toList()

        assertEquals(BuiltInCatalog.all.size, definitions.size)
        assertEquals(BuiltInCatalog.all.map { "plugin.${it.id}" }.sorted(), definitions.map { it.id }.sorted())
        assertTrue(definitions.all { it.origin == "builtin-plugin-catalog" })
        assertTrue(definitions.all { it.provenance.any { provenance -> provenance.sourceType == "sandbox-built-in-catalog" } })
        assertTrue(definitions.all { it.metadata["componentId"]?.isNotBlank() == true })
    }

    @Test
    fun `provider preserva metadados de instalacao e validacao sem autorizar execucao`() {
        val definition = PluginCatalogCapabilityProvider().capabilities().first { it.id == "plugin.ollama" }

        assertEquals("ollama", definition.metadata["componentId"])
        assertEquals("true", definition.metadata["installable"])
        assertTrue(definition.providedCapabilities.contains("plugin.ollama"))
        assertTrue(definition.requiredPermissions.isEmpty())
    }

    @Test
    fun `catalogo sem estado confirmado nao anuncia plugin como disponivel`() {
        val component = SandboxComponent(
            id = "example",
            name = "Example",
            description = "Example plugin",
            kind = ComponentKind.PLUGIN
        )

        val definition = PluginCatalogCapabilityProvider(
            components = { listOf(component) }
        ).capabilities().single()

        assertEquals(CapabilityAvailability.UNAVAILABLE, definition.availability)
    }

    @Test
    fun `somente instalacao confirmada anuncia plugin como disponivel`() {
        val component = SandboxComponent(
            id = "example",
            name = "Example",
            description = "Example plugin",
            kind = ComponentKind.PLUGIN
        )

        val definition = PluginCatalogCapabilityProvider(
            components = { listOf(component) },
            statusOf = { InstallationState.INSTALLED }
        ).capabilities().single()

        assertEquals(CapabilityAvailability.AVAILABLE, definition.availability)
    }
}
