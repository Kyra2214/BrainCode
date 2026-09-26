package com.brain.research

import java.util.Locale

/** Transforma perguntas conversacionais em consultas orientadas à tarefa, sem LLM. */
object ResearchQueryRewriter {

    // Trechos de "conversa" que atrapalham a busca e não fazem parte do tema em si.
    private val FILLERS = listOf(
        "quero fazer", "quero criar", "quero saber sobre", "quero saber",
        "quero entender como", "quero entender", "quero aprender sobre",
        "me explique sobre", "me explique", "me explica sobre", "me explica",
        "explique sobre", "explique", "explica sobre", "explica",
        "fale sobre", "fala sobre", "gostaria de saber sobre", "gostaria de saber",
        "preciso saber sobre", "preciso saber", "pode me explicar", "pode explicar",
        "oque preciso pra fazer", "o que preciso para fazer", "o que preciso pra fazer",
        "não quero produzir agora", "nao quero produzir agora",
        "não quero produzir", "nao quero produzir",
        "só quero saber sobre", "so quero saber sobre",
        "apenas quero saber sobre", "por favor"
    )

    fun rewrite(query: String): String {
        val cleaned = stripFillers(query)
        val normalized = cleaned.lowercase(Locale.ROOT).trim()
        return when {
            normalized.contains("como funciona") || normalized.startsWith("como ") ->
                "$cleaned arquitetura, componentes e funcionamento técnico"
            else ->
                "$cleaned explicação, definição e contexto"
        }
    }

    /** Remove frases de "conversa" (verbos + intenção) mantendo o assunto real. */
    private fun stripFillers(query: String): String {
        var result = query
        for (filler in FILLERS) {
            result = result.replace(Regex("(?i)\\b${Regex.escape(filler)}\\b"), " ")
        }
        return result
            .replace(Regex("\\s+"), " ")
            .trim()
            .trim(',', '.', ';', '!', '?')
            .ifBlank { query.trim() } // nunca deixa a busca vazia
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
