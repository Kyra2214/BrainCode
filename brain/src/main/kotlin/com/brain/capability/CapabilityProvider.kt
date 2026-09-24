package com.brain.capability

import com.brain.execution.RiskClass
import com.brain.router.ApiCatalog

/** Fonte de capabilities que pode ser carregada sob demanda pelo registry/discovery. */
interface CapabilityProvider {
    val providerId: String
    fun capabilities(): Sequence<CapabilityDefinition>
}

/** Adapta o ApiCatalog existente sem substituí-lo como catálogo operacional. */
class ApiCatalogCapabilityProvider(
    override val providerId: String,
    private val catalog: ApiCatalog
) : CapabilityProvider {
    override fun capabilities(): Sequence<CapabilityDefinition> = catalog.listarModelos().asSequence().map { model ->
        val id = "api.${model.providerId}.${model.modeloId}".replace(Regex("[^a-zA-Z0-9._-]"), "-")
        CapabilityDefinition(
            id = id,
            name = "${model.providerId}/${model.modeloId}",
            description = "Provider/modelo descoberto pelo ApiCatalog operacional",
            category = CapabilityCategory.API,
            ownerId = model.providerId,
            origin = providerId,
            requiredCapabilities = setOf("llm.reasoning"),
            providedCapabilities = setOf("llm.reasoning", "llm.${model.papeisSugeridos.joinToString("-").lowercase()}"),
            risk = RiskClass.LOW,
            cost = CostClass.FREE,
            reliability = catalog.statsAtuais(model.providerId, model.modeloId)?.taxaSucessoRecente ?: .5,
            quality = .5,
            supportsReasoning = true,
            availability = CapabilityAvailability.AVAILABLE,
            version = "1.0.0",
            providerIds = setOf(model.providerId),
            metadata = mapOf("model" to model.modeloId),
            provenance = listOf(CapabilityProvenance(providerId, "api-catalog", evidence = model.modeloId))
        )
    }
}

/** Carregamento hierárquico: providers só entram no registry quando solicitados. */
class LazyCapabilityDiscovery(
    private val registry: CapabilityRegistry,
    private val providers: Map<String, CapabilityProvider>
) {
    fun load(providerIds: Set<String> = providers.keys): Int {
        var loaded = 0
        providerIds.forEach { id ->
            providers[id]?.capabilities()?.forEach { registry.register(it); loaded++ }
        }
        return loaded
    }
}
