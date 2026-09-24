package com.sandbox.app

import com.brain.capability.CapabilityAvailability
import com.brain.capability.CapabilityCategory
import com.brain.capability.CapabilityDefinition
import com.brain.capability.CapabilityProvenance
import com.brain.capability.CapabilityProvider
import com.brain.execution.RiskClass
import com.sandbox.sandbox.BuiltInCatalog
import com.sandbox.sandbox.InstallationState
import com.sandbox.sandbox.SandboxComponent

/**
 * Adapta o catálogo declarativo de plugins do Sandbox ao registry universal
 * do Brain. A entrada descreve o plugin e sua procedência; não concede
 * autorização nem transforma comandos de instalação em shell executável.
 */
class PluginCatalogCapabilityProvider(
    private val components: () -> List<SandboxComponent> = { BuiltInCatalog.all },
    private val statusOf: (String) -> InstallationState? = { null },
    override val providerId: String = "builtin-plugin-catalog"
) : CapabilityProvider {
    override fun capabilities(): Sequence<CapabilityDefinition> = components().asSequence().map { component ->
        val capabilityId = "plugin.${component.id}"
        CapabilityDefinition(
            id = capabilityId,
            name = component.name,
            description = component.description,
            category = CapabilityCategory.TOOL,
            ownerId = "sandbox-plugin:${component.id}",
            origin = providerId,
            supportsCode = component.kind.name == "TOOL",
            risk = RiskClass.LOW,
            // O catálogo descreve o que pode existir; somente uma instalação
            // confirmada deve ser anunciada como disponível para execução.
            availability = if (statusOf(component.id) == InstallationState.INSTALLED) {
                CapabilityAvailability.AVAILABLE
            } else {
                CapabilityAvailability.UNAVAILABLE
            },
            version = component.version ?: "catalog",
            providedCapabilities = setOf(capabilityId),
            metadata = buildMap {
                put("componentId", component.id)
                put("componentKind", component.kind.name)
                put("validationCommand", component.validationCommand.joinToString(" "))
                put("installable", (component.installCommand != null).toString())
            },
            provenance = listOf(
                CapabilityProvenance(
                    sourceId = providerId,
                    sourceType = "sandbox-built-in-catalog",
                    evidence = component.id
                )
            )
        )
    }
}
