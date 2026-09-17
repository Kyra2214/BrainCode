package com.brain.reasoning

import com.brain.prompt.PromptDomain

data class RequirementDiscoveryResult(
    val explicit: List<Requirement>,
    val missing: List<String>
)

/** Slot filling determinístico: separa o que foi pedido do que ainda falta perguntar. */
class RequirementDiscovery {
    fun discover(request: String, domain: PromptDomain): RequirementDiscoveryResult {
        val lower = request.lowercase()
        val explicit = buildList {
            if (lower.containsAny("app", "aplicativo", "interface", "tela", "layout")) add(Requirement("interface/aplicativo"))
            if (lower.containsAny("céu estrelado", "ceu estrelado")) add(Requirement("céu estrelado ao fundo"))
            if ("deserto" in lower) add(Requirement("deserto"))
            if (lower.containsAny("meteoro", "meteorito")) add(Requirement("meteoros caindo"))
            if (lower.containsAny("fotorrealista", "fotografia", "foto")) add(Requirement("estilo fotográfico/fotorrealista"))
            if (lower.containsAny("iluminação cinematográfica", "iluminacao cinematografica")) add(Requirement("iluminação cinematográfica"))
        }.distinctBy { it.text }
        val missing = when {
            domain == PromptDomain.CODIGO && explicit.none { it.text == "interface/aplicativo" } -> listOf("finalidade do código ou interface")
            else -> emptyList()
        }
        return RequirementDiscoveryResult(explicit, missing)
    }

    private fun String.containsAny(vararg terms: String): Boolean = terms.any { it in this }
}
