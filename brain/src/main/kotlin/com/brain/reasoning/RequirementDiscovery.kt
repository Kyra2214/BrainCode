package com.brain.reasoning

import com.brain.prompt.PromptDomain

enum class RequirementKind { SUBJECT, ACTION, ENVIRONMENT, STYLE, UI, OUTPUT, CONSTRAINT }

data class RequirementSlot(
    val name: String,
    val value: String,
    val kind: RequirementKind,
    val explicit: Boolean = true
)

data class RequirementConstraint(val text: String, val source: String = "user")
data class RequirementDependency(val requirement: String, val dependsOn: String)

data class RequirementDiscoveryResult(
    val explicit: List<Requirement>,
    val missing: List<String>,
    val slots: List<RequirementSlot> = emptyList(),
    val constraints: List<RequirementConstraint> = emptyList(),
    val dependencies: List<RequirementDependency> = emptyList()
)

/** Descoberta semântica local: intenção preenchida em slots antes de virar texto de prompt. */
class RequirementDiscovery {
    fun discover(request: String, domain: PromptDomain): RequirementDiscoveryResult {
        val normalized = request.trim().replace(Regex("\\s+"), " ")
        val lower = normalized.lowercase()
        val slots = mutableListOf<RequirementSlot>()
        val constraints = mutableListOf<RequirementConstraint>()

        fun slot(name: String, value: String, kind: RequirementKind) {
            val clean = value.trim().trim(',', '.', ';')
            if (clean.isNotBlank()) slots += RequirementSlot(name, clean, kind)
        }
        if (domain == PromptDomain.CODIGO || lower.containsAny("app", "aplicativo", "interface", "tela", "layout")) {
            slot("produto", "interface/aplicativo", RequirementKind.UI)
        }
        extractAfterAny(lower, normalized, "fundo", "ambientado em", "ambiente")?.let { slot("ambiente", it, RequirementKind.ENVIRONMENT) }
        extractAfterAny(lower, normalized, "adicione", "adicionar", "inclua", "incluir")?.let { slot("elementos", it, RequirementKind.ACTION) }
        extractAfterAny(lower, normalized, "mude", "mudar", "troque", "trocar", "substitua", "substituir")?.let { slot("alteração", it, RequirementKind.ACTION) }
        if (lower.containsAny("fotorrealista", "fotografia", "foto")) slot("estilo", "fotorrealista", RequirementKind.STYLE)
        if (lower.containsAny("iluminação cinematográfica", "iluminacao cinematografica")) slot("iluminação", "cinematográfica", RequirementKind.STYLE)
        if (lower.containsAny("céu estrelado", "ceu estrelado")) slot("ambiente", "céu estrelado ao fundo", RequirementKind.ENVIRONMENT)
        if ("deserto" in lower) slot("ambiente", "deserto", RequirementKind.ENVIRONMENT)
        if (lower.containsAny("meteoro", "meteorito")) slot("elementos", "meteoros caindo", RequirementKind.ACTION)
        extractSubject(normalized)?.let { slot("sujeito", it, RequirementKind.SUBJECT) }

        if (lower.containsAny("sem", "não pode", "nao pode", "obrigatório", "obrigatorio")) {
            constraints += RequirementConstraint(extractConstraint(normalized))
        }
        val explicit = slots.map { Requirement(it.value) }.distinctBy { it.text }
        val missing = when {
            domain == PromptDomain.CODIGO && slots.none { it.kind == RequirementKind.UI } -> listOf("finalidade do código ou interface")
            domain == PromptDomain.IMAGEM && slots.none { it.kind == RequirementKind.SUBJECT } -> listOf("sujeito principal")
            else -> emptyList()
        }
        val dependencies = buildList {
            if (slots.any { it.kind == RequirementKind.ACTION } && slots.any { it.kind == RequirementKind.SUBJECT }) {
                add(RequirementDependency("elementos", "sujeito"))
            }
            if (slots.any { it.kind == RequirementKind.STYLE } && slots.any { it.kind == RequirementKind.SUBJECT }) {
                add(RequirementDependency("estilo", "sujeito"))
            }
        }
        return RequirementDiscoveryResult(explicit, missing, slots.distinctBy { it.name + it.value }, constraints, dependencies)
    }

    private fun extractSubject(text: String): String? {
        val match = Regex("(?i)\\b(?:de|sobre|com)\\s+(?:um|uma|o|a)?\\s*([^,.;]+)").find(text)
        return match?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.length >= 3 }
    }

    private fun extractAfterAny(lower: String, original: String, vararg markers: String): String? {
        val marker = markers.firstOrNull { lower.contains(it) } ?: return null
        val start = lower.indexOf(marker) + marker.length
        val tail = original.substring(start).trim()
        return tail.split(Regex("(?i)\\s+(?:e|mas|para|com)\\s+|[,.;]")).firstOrNull()?.trim()?.takeIf { it.length >= 3 }
    }

    private fun extractConstraint(text: String): String =
        Regex("(?i)\\bsem\\s+(.+)").find(text)?.groupValues?.getOrNull(1)?.trim().orEmpty().ifBlank { text }
    private fun String.containsAny(vararg terms: String): Boolean = terms.any { it in this }
}
