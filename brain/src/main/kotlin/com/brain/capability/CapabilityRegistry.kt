package com.brain.capability

import com.brain.execution.RiskClass
import java.util.concurrent.ConcurrentHashMap

/**
 * Registry único de metadados de capabilities do BrainCode.
 *
 * APIs, providers, agents, skills, tools, commands, sandbox capabilities,
 * workflows e componentes internos entram como CapabilityDefinition. Este
 * registry não substitui o ApiCatalog operacional, o SkillRegistry de
 * assinatura/revogação ou o PolicyBroker: ele é a camada comum de descoberta
 * declarativa que evita catálogos paralelos com a mesma responsabilidade.
 */
class CapabilityRegistry(initial: Iterable<CapabilityDefinition> = emptyList()) {
    private val definitions = ConcurrentHashMap<String, CapabilityDefinition>()
    private val lock = Any()

    init {
        initial.forEach(::register)
    }

    /** Registra uma entrada nova; atualização deve ser explícita via update(). */
    fun register(definition: CapabilityDefinition): CapabilityDefinition = synchronized(lock) {
        require(definitions.putIfAbsent(definition.id, definition) == null) {
            "capability já registrada: ${definition.id}"
        }
        definition
    }

    /** Atualiza somente uma entrada já existente e preserva sua identidade. */
    fun update(definition: CapabilityDefinition): CapabilityDefinition = synchronized(lock) {
        require(definitions.containsKey(definition.id)) {
            "capability não registrada: ${definition.id}"
        }
        definitions[definition.id] = definition
        definition
    }

    fun remove(id: String): CapabilityDefinition? = synchronized(lock) {
        definitions.remove(id)
    }

    fun getById(id: String): CapabilityDefinition? = definitions[id]

    fun all(): List<CapabilityDefinition> = definitions.values.sortedBy { it.id }

    fun findByCategory(category: CapabilityCategory): List<CapabilityDefinition> =
        all().filter { it.category == category }

    fun findByCapability(capability: String): List<CapabilityDefinition> {
        require(capability.isNotBlank()) { "capability consultada não pode ser vazia" }
        return all().filter { it.provides(capability) }
    }

    fun findByRisk(risk: RiskClass): List<CapabilityDefinition> =
        all().filter { it.risk == risk }

    fun findByCost(cost: CostClass): List<CapabilityDefinition> =
        all().filter { it.cost == cost }

    fun findByProvider(providerId: String): List<CapabilityDefinition> {
        require(providerId.isNotBlank()) { "providerId consultado não pode ser vazio" }
        return all().filter {
            providerId == it.ownerId || providerId == it.origin || providerId in it.providerIds
        }
    }

    /**
     * Consulta estrutural progressiva. Não consulta rede, não aplica Policy e
     * não escolhe executor; o Capability Discovery posterior pode ranquear o
     * resultado e aplicar o PolicyBroker.
     */
    fun discover(query: CapabilityQuery = CapabilityQuery()): List<CapabilityDefinition> = all()
        .asSequence()
        .filter { query.categories.isEmpty() || it.category in query.categories }
        .filter { query.requiredCapabilities.all(it::provides) }
        .filter { query.providerIds.isEmpty() || query.providerIds.any { provider -> provider in it.providerIds || provider == it.ownerId || provider == it.origin } }
        .filter { query.riskAtMost == null || it.risk.rank() <= query.riskAtMost.rank() }
        .filter { query.costAtMost == null || it.cost.rank <= query.costAtMost.rank }
        .filter { !query.supportsFiles || it.supportsFiles }
        .filter { !query.supportsWeb || it.supportsWeb }
        .filter { !query.supportsCode || it.supportsCode }
        .filter { !query.supportsReasoning || it.supportsReasoning }
        .filter { !query.availableOnly || it.isDiscoverable() }
        .sortedBy { it.id }
        .toList()
}
