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
        val normalizedQuery = query.lowercase(Locale.ROOT)
        val factual = Regex("(?i)\\b(tempo|clima|temperatura|previsão|previsao|cotação|cotacao|preço|preco|data|horário|horario|placar)\\b")
            .containsMatchIn(normalizedQuery)

        val topicTerms = Regex("[\\p{L}\\p{N}]{4,}")
            .findAll(normalizedQuery)
            .map { it.value }
            .filterNot { it in setOf("como", "funciona", "sobre", "explique", "fale", "qual", "quais", "hoje", "agora") }
            .toSet()

        val sentences = sources.asSequence()
            .flatMap { source ->
                source.relevantContent
                    .replace(Regex("<[^>]+>"), " ")
                    .replace(Regex("https?://\\S+"), " ")
                    .replace(Regex("\\s+"), " ")
                    .split(Regex("(?<=[.!?])\\s+|\\n+"))
                    .asSequence()
            }
            .map { it.trim() }
            .filter { it.isNotBlank() && !isBoilerplate(it) }
            .filter { sentence ->
                topicTerms.isEmpty() || topicTerms.any { term ->
                    sentence.lowercase(Locale.ROOT).contains(term)
                }
            }
            .distinct()
            .toList()

        val selected = sentences.take(if (factual) 2 else 4)
        if (selected.isEmpty()) {
            return "Não encontrei informação suficientemente relacionada ao tema para responder com segurança."
        }

        return selected.joinToString(" ").take(if (factual) 700 else 1600)
    }

    private fun isBoilerplate(sentence: String): Boolean {
        val normalized = sentence.lowercase(Locale.ROOT).replace(Regex("\\s+"), " ").trim()
        if (normalized.length < 3) return true
        val uiPattern = Regex(
            "(?i)\\b(cookie|cookies|privacidade|privacy|termos de uso|terms of use|aceitar|accept|recusar|reject|login|log in|sign in|sign up|inscreva-se|menu|navigation|navegação|idioma|language|home|subscribe|assine|advertise|anuncie|javascript)\\b"
        )
        val navigationLike = normalized.count { it == '|' || it == '›' || it == '·' } >= 2 ||
            (normalized.split(Regex("[,|]")).size >= 5 && normalized.length < 180)
        return uiPattern.containsMatchIn(normalized) || navigationLike
    }
}
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
