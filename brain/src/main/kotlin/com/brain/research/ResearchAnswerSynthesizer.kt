package com.brain.research

import java.util.Locale

/** Transforma perguntas conversacionais em consultas orientadas à tarefa, sem LLM. */
object ResearchQueryRewriter {
    fun rewrite(query: String): String {
        val normalized = query.lowercase(Locale.ROOT).trim()
        return when {
            normalized.contains("como funciona") || normalized.startsWith("como ") ->
                "$query architecture components implementation and technical explanation"
            else -> "$query explanation definition context and relevant facts"
        }
    }
}

/**
 * Entrega uma resposta humana a partir dos dados coletados. Títulos, nomes de
 * providers, URLs e metadados ficam apenas em citations/evidence.
 */
object ResearchAnswerSynthesizer {
    fun synthesize(query: String, sources: List<ResearchResult>): String {
        val useful = sources.asSequence()
            .map { it.relevantContent.trim().replace(Regex("\\s+"), " ") }
            .filter { it.isNotBlank() }
            .map { it.take(360) }
            .distinct()
            .take(3)
            .toList()
        return if (useful.isEmpty()) {
            "Encontrei fontes, mas elas não continham informação suficiente para responder com segurança."
        } else {
            val sourceNames = sources.mapNotNull { runCatching { java.net.URI(it.url).host }.getOrNull() }.distinct()
            "A resposta encontrada indica os seguintes pontos principais:\n" +
                useful.joinToString("\n") { "• $it" } +
                if (sourceNames.isEmpty()) "" else "\n\nFontes consultadas: ${sourceNames.joinToString(", ")}."
        }
    }
}
