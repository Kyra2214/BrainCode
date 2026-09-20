package com.sandbox.app

import java.util.Locale

/** Tipo semântico do conteúdo produzido por um agente, independente da sua apresentação visual. */
enum class GeneratedContentType { TEXT, CLARIFICATION, PROMPT, CODE, MARKDOWN, SCRIPT, JSON, YAML }

data class ResearchSourceUi(
    val title: String,
    val source: String,
    val url: String,
    val summary: String
)

data class PromptReasoningUi(
    val intent: String,
    val mandatoryElements: List<String>,
    val evidence: List<String>,
    val assumptions: List<String>,
    val revisions: List<String>
)

/**
 * Extrai somente o conteúdo produzido, removendo o envelope operacional do Brain.
 * Não altera o texto bruto usado para renderização de código/Markdown.
 */
fun copyPayloadFor(content: String, type: GeneratedContentType): String = when (type) {
    GeneratedContentType.PROMPT -> content
        .replace(Regex("(?is)^.*?qualidade\\s+\\d+%[^\\n]*\\n*"), "")
        .substringBefore("\n\nContexto pesquisado considerado:")
        .substringBefore("\n\nTécnicas consideradas a partir da pesquisa:")
        .substringBefore("\n\nReferência da biblioteca considerada")
        .substringBefore("\n\n(Melhoria por IA não está disponível")
        .deduplicateTechnicalBlocks()
        .trim()
    else -> content
}

/** Remove repetições do mesmo bloco técnico quando fontes antigas são concatenadas. */
private fun String.deduplicateTechnicalBlocks(): String {
    val labels = listOf("estilo:", "composição:", "composicao:", "iluminação:", "iluminacao:", "câmera:", "camera:", "realismo:")
    val seen = mutableSetOf<String>()
    return lineSequence()
        .filter { line ->
            val normalized = line.trim().lowercase(Locale.ROOT)
            val isTechnical = labels.any { normalized.startsWith(it) }
            !isTechnical || seen.add(normalized)
        }
        .joinToString("\n")
        .replace(Regex("\n{3,}"), "\n\n")
}

fun detectGeneratedContentType(content: String, capability: String? = null, evidence: Iterable<String> = emptyList()): GeneratedContentType {
    val lower = content.lowercase()
    if (evidence.any { it == "prompt-checklist:aguardando-esclarecimento" }) return GeneratedContentType.CLARIFICATION
    if (capability == "prompt.library.write" || lower.contains("prompt gerado") || lower.contains("prompt novo localmente")) return GeneratedContentType.PROMPT
    if (Regex("(?s)```(bash|sh|shell|zsh)\\b").containsMatchIn(lower) || lower.contains("#!/bin/")) return GeneratedContentType.SCRIPT
    if (Regex("(?s)```(json)\\b").containsMatchIn(lower)) return GeneratedContentType.JSON
    if (Regex("(?s)```(yaml|yml)\\b").containsMatchIn(lower)) return GeneratedContentType.YAML
    if (content.contains("```") && lower.contains("markdown")) return GeneratedContentType.MARKDOWN
    if (content.contains("```") || capability == "sandbox.code") return GeneratedContentType.CODE
    return GeneratedContentType.TEXT
}
