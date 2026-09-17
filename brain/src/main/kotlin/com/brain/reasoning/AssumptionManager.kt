package com.brain.reasoning

import com.brain.prompt.PromptDomain

data class AssumptionDecision(
    val assumptions: List<String>,
    val blocked: List<String> = emptyList()
)

/** Diferencia lacuna segura de requisito importante: não inventa campos críticos. */
class AssumptionManager {
    fun decide(request: String, domain: PromptDomain, requirements: List<Requirement>): AssumptionDecision {
        val lower = request.lowercase()
        val assumptions = buildList {
            when (domain) {
                PromptDomain.IMAGEM, PromptDomain.VIDEO -> add("usar composição equilibrada quando o enquadramento não for especificado")
                PromptDomain.CODIGO -> add("preservar padrões convencionais da plataforma quando a implementação não for especificada")
                PromptDomain.TEXTO -> add("usar estrutura clara quando o formato não for especificado")
            }
        }
        val blocked = if (domain == PromptDomain.CODIGO && requirements.none { it.text == "interface/aplicativo" } &&
            lower.containsAny("crie", "implemente", "desenvolva")) {
            listOf("finalidade do código ou interface")
        } else emptyList()
        return AssumptionDecision(assumptions, blocked)
    }

    private fun String.containsAny(vararg terms: String): Boolean = terms.any { it in this }
}
