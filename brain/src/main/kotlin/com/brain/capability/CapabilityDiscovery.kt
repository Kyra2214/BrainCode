package com.brain.capability

import com.brain.execution.RiskClass

/** Entrada de intenção; não contém executor nem nome de provider obrigatório. */
data class CapabilityIntent(
    val objective: String,
    val requiredCapabilities: Set<String> = emptySet(),
    val categories: Set<CapabilityCategory> = emptySet(),
    val riskAtMost: RiskClass? = null,
    val costAtMost: CostClass? = null,
    val supportsFiles: Boolean = false,
    val supportsWeb: Boolean = false,
    val supportsCode: Boolean = false,
    val supportsReasoning: Boolean = false,
    val preferredProviders: Set<String> = emptySet(),
    val context: Set<String> = emptySet(),
    val maxCandidates: Int = 5
) {
    init {
        require(objective.isNotBlank()) { "objetivo da intenção é obrigatório" }
        require(requiredCapabilities.none { it.isBlank() }) { "capability requerida não pode ser vazia" }
        require(preferredProviders.none { it.isBlank() }) { "provider preferido não pode ser vazio" }
        require(maxCandidates > 0) { "maxCandidates deve ser positivo" }
    }
}

data class CapabilityCandidate(
    val capability: CapabilityDefinition,
    val score: Double,
    val reasons: List<String>
)

data class CapabilityDiscoveryResult(
    val categories: Set<CapabilityCategory>,
    val considered: Int,
    val policyRejected: Int,
    val candidates: List<CapabilityCandidate>
)

/**
 * Descoberta hierárquica: intenção → categoria → candidatas → policy → ranking
 * → candidatos finais. A função de policy é injetada para evitar que Discovery
 * se torne uma segunda autoridade de autorização.
 */
class CapabilityDiscovery(private val registry: CapabilityRegistry) {
    fun discover(
        intent: CapabilityIntent,
        policyAllows: (CapabilityDefinition) -> Boolean = { true }
    ): CapabilityDiscoveryResult {
        val categories = intent.categories.ifEmpty { inferCategories(intent) }
        // Uma capability explícita já identifica a intenção estrutural;
        // não deixar inferência textual eliminar Tool/Sandbox/Agent/API válidos.
        val queryCategories = if (intent.categories.isEmpty() && intent.requiredCapabilities.isNotEmpty()) emptySet() else categories
        val query = CapabilityQuery(
            categories = queryCategories,
            requiredCapabilities = intent.requiredCapabilities,
            providerIds = intent.preferredProviders,
            riskAtMost = intent.riskAtMost,
            costAtMost = intent.costAtMost,
            supportsFiles = intent.supportsFiles,
            supportsWeb = intent.supportsWeb,
            supportsCode = intent.supportsCode,
            supportsReasoning = intent.supportsReasoning
        )
        val structural = registry.discover(query)
        val allowed = structural.filter(policyAllows)
        val ranked = allowed
            .map { candidate -> candidate to score(candidate, intent, categories) }
            .sortedWith(compareByDescending<Pair<CapabilityDefinition, Double>> { it.second }.thenBy { it.first.id })
            .take(intent.maxCandidates)
            .map { (candidate, score) -> CapabilityCandidate(candidate, score, reasons(candidate, intent, categories)) }

        return CapabilityDiscoveryResult(
            categories = categories,
            considered = structural.size,
            policyRejected = structural.size - allowed.size,
            candidates = ranked
        )
    }

    private fun score(
        candidate: CapabilityDefinition,
        intent: CapabilityIntent,
        categories: Set<CapabilityCategory>
    ): Double {
        val categoryMatch = if (candidate.category in categories) 1.0 else 0.0
        val requestedMatches = if (intent.requiredCapabilities.isEmpty()) 1.0 else
            intent.requiredCapabilities.count(candidate::provides).toDouble() / intent.requiredCapabilities.size
        val providerMatch = if (intent.preferredProviders.isEmpty()) 0.5 else
            if (intent.preferredProviders.any { it == candidate.ownerId || it == candidate.origin || it in candidate.providerIds }) 1.0 else 0.0
        val latency = candidate.estimatedLatencyMs?.let { (1.0 - it / 10_000.0).coerceIn(0.0, 1.0) } ?: 0.5
        val context = contextScore(candidate, intent.context)
        val risk = riskScore(candidate.risk, intent.riskAtMost)
        val cost = costScore(candidate.cost, intent.costAtMost)
        return categoryMatch * 0.15 + requestedMatches * 0.25 + candidate.quality * 0.20 +
            candidate.reliability * 0.15 + latency * 0.10 + context * 0.05 +
            providerMatch * 0.05 + risk * 0.025 + cost * 0.025
    }

    private fun contextScore(candidate: CapabilityDefinition, context: Set<String>): Double {
        if (context.isEmpty()) return 0.5
        val tags = candidate.metadata.keys + candidate.metadata.values
        return context.count { requested -> requested in tags }.toDouble() / context.size
    }

    private fun riskScore(risk: RiskClass, limit: RiskClass?): Double {
        if (limit == null) return 0.5
        val distance = limit.rank() - risk.rank()
        return when {
            distance < 0 -> 0.0
            distance == 0 -> 0.75
            else -> 1.0
        }
    }

    private fun costScore(cost: CostClass, limit: CostClass?): Double {
        if (limit == null) return 0.5
        return if (cost.rank <= limit.rank) 1.0 else 0.0
    }

    private fun reasons(
        candidate: CapabilityDefinition,
        intent: CapabilityIntent,
        categories: Set<CapabilityCategory>
    ): List<String> = buildList {
        if (candidate.category in categories) add("categoria compatível: ${candidate.category}")
        if (intent.requiredCapabilities.any(candidate::provides)) add("fornece capability requerida")
        add("quality=${"%.2f".format(candidate.quality)}")
        add("reliability=${"%.2f".format(candidate.reliability)}")
        if (candidate.availability == CapabilityAvailability.AVAILABLE) add("disponível")
    }

    private fun inferCategories(intent: CapabilityIntent): Set<CapabilityCategory> {
        val text = intent.objective.lowercase()
        return buildSet {
            if (intent.supportsWeb || containsAny(text, "web", "internet", "github", "pesquis", "url")) add(CapabilityCategory.API)
            if (intent.supportsCode || containsAny(text, "código", "codigo", "build", "teste", "debug")) add(CapabilityCategory.SANDBOX)
            if (intent.supportsReasoning || containsAny(text, "planej", "arquitet", "racioc", "analis")) add(CapabilityCategory.AGENT)
            if (containsAny(text, "skill", "procedimento", "rotina")) add(CapabilityCategory.SKILL)
            if (containsAny(text, "arquivo", "ler", "escrever", "ferramenta")) add(CapabilityCategory.TOOL)
            if (isEmpty()) add(CapabilityCategory.INTERNAL)
        }
    }

    private fun containsAny(text: String, vararg terms: String): Boolean = terms.any(text::contains)
}
