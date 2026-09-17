package com.brain.reasoning

import com.brain.prompt.PromptDomain

/** Tipo de tarefa reconhecido antes de planejar qualquer capability. */
enum class ReasoningIntent {
    CREATE_PROMPT,
    REFINE_PROMPT,
    IMPROVE_PROMPT,
    BUILD_CODE,
    GENERAL_TEXT
}

data class Requirement(
    val text: String,
    val explicit: Boolean = true
)

data class ReasoningState(
    val objective: String,
    val intent: ReasoningIntent,
    val domain: PromptDomain,
    val requirements: List<Requirement>,
    val assumptions: List<String>,
    val missing: List<String>
) {
    val canProceedLocally: Boolean get() = missing.isEmpty()
}

/** Interpretação determinística: não executa ações, não usa LLM e não inventa requisitos críticos. */
class ReasoningEngine {
    fun analyze(request: String): ReasoningState {
        val objective = request.trim()
        require(objective.isNotBlank()) { "objetivo não pode ser vazio" }
        val lower = objective.lowercase()
        val domain = PromptDomain.classificar(objective)
        val intent = when {
            isRefinement(lower) -> if (isExplicitImprovement(lower)) ReasoningIntent.IMPROVE_PROMPT else ReasoningIntent.REFINE_PROMPT
            domain == PromptDomain.CODIGO -> ReasoningIntent.BUILD_CODE
            lower.contains("prompt") -> ReasoningIntent.CREATE_PROMPT
            else -> ReasoningIntent.GENERAL_TEXT
        }
        val requirements = discoverRequirements(objective, domain)
        val missing = discoverMissing(objective, domain)
        val assumptions = safeAssumptions(objective, domain)
        return ReasoningState(objective, intent, domain, requirements, assumptions, missing)
    }

    private fun discoverRequirements(request: String, domain: PromptDomain): List<Requirement> {
        val lower = request.lowercase()
        val found = buildList {
            if (domain == PromptDomain.CODIGO && lower.containsAny("app", "aplicativo", "interface", "tela", "layout")) add(Requirement("interface/aplicativo"))
            if (lower.containsAny("céu estrelado", "ceu estrelado")) add(Requirement("céu estrelado ao fundo"))
            if (lower.contains("deserto")) add(Requirement("deserto"))
            if (lower.containsAny("meteoro", "meteorito")) add(Requirement("meteoros caindo"))
            if (lower.containsAny("fotorrealista", "fotografia", "foto")) add(Requirement("estilo fotográfico/fotorrealista"))
            if (lower.containsAny("iluminação cinematográfica", "iluminacao cinematografica")) add(Requirement("iluminação cinematográfica"))
        }
        return found.distinctBy { it.text }
    }

    private fun discoverMissing(request: String, domain: PromptDomain): List<String> {
        val lower = request.lowercase()
        if (domain != PromptDomain.CODIGO) return emptyList()
        if (lower.containsAny("app", "aplicativo", "interface", "tela", "layout")) return emptyList()
        return listOf("finalidade do código ou interface")
    }

    private fun safeAssumptions(request: String, domain: PromptDomain): List<String> = when (domain) {
        PromptDomain.IMAGEM, PromptDomain.VIDEO -> listOf("usar composição equilibrada quando o enquadramento não for especificado")
        PromptDomain.CODIGO -> listOf("preservar padrões convencionais da plataforma quando a implementação não for especificada")
        else -> emptyList()
    }

    private fun isRefinement(lower: String): Boolean = lower.containsAny(
        "mude", "muda", "troque", "troca", "adicione", "adiciona", "substitua", "substitui", "melhore", "melhora", "otimize", "otimiza"
    )

    private fun isExplicitImprovement(lower: String): Boolean = lower.containsAny("melhore", "melhora", "otimize", "otimiza", "mais profissional", "reformule")

    private fun String.containsAny(vararg terms: String): Boolean = terms.any { it in this }
}
