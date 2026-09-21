package com.brain.agent

import com.brain.research.WebProviderSet

/** Registro de especialistas; seleção ocorre por contrato/capability, não por nome do projeto. */
data class AgentDescriptor(
    val id: String,
    val category: AgentCategory,
    val contract: String,
    val provenance: String,
    val license: String,
    val enabled: Boolean = true,
    val capabilities: Set<String> = emptySet()
)

enum class AgentCategory { CONVERSATION, RESEARCH, WEB, SYSTEM }

class AgentRegistry(
    descriptors: List<AgentDescriptor> = emptyList()
) {
    private val entries = descriptors.associateBy { it.id }.toMutableMap()

    fun register(descriptor: AgentDescriptor) {
        require(descriptor.id.isNotBlank())
        require(descriptor.contract.isNotBlank())
        entries[descriptor.id] = descriptor
    }

    fun resolve(category: AgentCategory, capability: String): AgentDescriptor? = entries.values
        .firstOrNull { it.enabled && it.category == category && capability in it.capabilities }

    fun all(): List<AgentDescriptor> = entries.values.sortedBy { it.id }

    companion object {
        fun default(): AgentRegistry = AgentRegistry(listOf(
            AgentDescriptor("conversation.no-inference", AgentCategory.CONVERSATION, "ConversationResponse", "TheShovel/no-inference adapter", "AGPL-3.0", capabilities = setOf("chat.respond")),
            AgentDescriptor("research.brain-harness", AgentCategory.RESEARCH, "ResearchRunResult", "BrainCode WebResearchAgent; SearchClaw concepts", "BrainCode", capabilities = setOf("network.research")),
            AgentDescriptor("web.providers", AgentCategory.WEB, "WebProviderSet", "BrainCode provider interfaces; Firecrawl concepts", "MIT-compatible adapter", capabilities = setOf("web.search", "web.fetch", "web.browser", "web.extract"))
        ))
    }
}

fun AgentRegistry.withWebProviders(providers: WebProviderSet): AgentRegistry {
    if (providers.search.isNotEmpty() || providers.fetch.isNotEmpty() || providers.browser.isNotEmpty() || providers.extraction.isNotEmpty()) {
        register(AgentDescriptor("web.providers.active", AgentCategory.WEB, "WebProviderSet", "injected provider set", "caller-owned", capabilities = setOf("web.search", "web.fetch", "web.browser", "web.extract")))
    }
    return this
}
