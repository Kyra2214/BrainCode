package com.sandbox.app

/** Tipo semântico do conteúdo produzido por um agente, independente da sua apresentação visual. */
enum class GeneratedContentType { TEXT, CLARIFICATION, PROMPT, CODE, MARKDOWN, SCRIPT, JSON, YAML }

data class ResearchSourceUi(
    val title: String,
    val source: String,
    val url: String,
    val summary: String
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
        .trim()
    else -> content
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
