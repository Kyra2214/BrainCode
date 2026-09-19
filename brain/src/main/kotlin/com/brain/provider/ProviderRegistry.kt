package com.brain.provider

import com.brain.router.ProviderModel
import java.util.concurrent.ConcurrentHashMap

/** Registro runtime de providers; credenciais continuam fora do registry. */
data class ProviderRegistration(
    val providerId: String,
    val displayName: String,
    val client: ProviderClient,
    val capabilities: Set<String> = emptySet(),
    val models: List<ProviderModel> = emptyList(),
    val enabled: Boolean = true,
    val metadata: Map<String, String> = emptyMap()
) {
    init {
        require(providerId.isNotBlank()) { "providerId é obrigatório" }
        require(displayName.isNotBlank()) { "displayName é obrigatório" }
        require(capabilities.none { it.isBlank() }) { "capability não pode ser vazia" }
        require(models.all { it.providerId == providerId }) { "modelo pertence a outro provider" }
        require(metadata.keys.none { it.isBlank() }) { "chave de metadata não pode ser vazia" }
    }

    fun supports(capability: String): Boolean = capability in capabilities
}

/** Fonte única dos providers disponíveis para discovery e execução. */
interface ProviderRegistry {
    fun register(provider: ProviderRegistration)
    fun replace(provider: ProviderRegistration)
    fun remove(providerId: String): Boolean
    fun find(providerId: String): ProviderRegistration?
    fun list(enabledOnly: Boolean = false): List<ProviderRegistration>
    fun findForCapability(capability: String): List<ProviderRegistration>
    fun modelsForCapability(capability: String): List<ProviderModel>
}

/** Implementação determinística para runtime local e testes. */
class InMemoryProviderRegistry : ProviderRegistry {
    private val providers = ConcurrentHashMap<String, ProviderRegistration>()

    override fun register(provider: ProviderRegistration) {
        check(providers.putIfAbsent(provider.providerId, provider) == null) {
            "providerId já registrado: ${provider.providerId}"
        }
    }

    override fun replace(provider: ProviderRegistration) {
        check(providers.replace(provider.providerId, provider) != null) {
            "providerId não registrado: ${provider.providerId}"
        }
    }

    override fun remove(providerId: String): Boolean = providers.remove(providerId) != null

    override fun find(providerId: String): ProviderRegistration? = providers[providerId]

    override fun list(enabledOnly: Boolean): List<ProviderRegistration> = providers.values
        .asSequence()
        .filter { !enabledOnly || it.enabled }
        .sortedBy { it.providerId }
        .toList()

    override fun findForCapability(capability: String): List<ProviderRegistration> =
        list(enabledOnly = true).filter { it.supports(capability) }

    override fun modelsForCapability(capability: String): List<ProviderModel> =
        findForCapability(capability).flatMap { it.models }
}
