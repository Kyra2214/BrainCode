package com.brain.text

/**
 * Heurística deliberadamente pequena para não transformar o TermMatcher em parser semântico.
 * Ela só bloqueia uma ocorrência quando há um marcador negativo imediatamente antes da ação,
 * na mesma oração curta. Ocorrências positivas em outra oração continuam autorizadas.
 */
object IntentNegation {
    private val negativeMarker = Regex("(?i)(?:\\bnao\\b|\\bnão\\b|\\bsem\\b|\\bevite\\b)")
    private val boundary = Regex("(?i)[,;.!?\\n]|\\b(?:e|mas|por[eé]m|apenas|somente)\\b")

    fun hasAllowedOccurrence(text: String, terms: Iterable<String>): Boolean =
        occurrences(text, terms).any { !isNegated(text, it.first) }

    fun hasAllowedOccurrence(text: String, vararg terms: String): Boolean =
        hasAllowedOccurrence(text, terms.asIterable())

    fun hasNegatedOccurrence(text: String, terms: Iterable<String>): Boolean =
        occurrences(text, terms).any { isNegated(text, it.first) }

    private fun occurrences(text: String, terms: Iterable<String>): List<Pair<Int, Int>> {
        val normalized = text.lowercase()
        return terms.flatMap { term ->
            val value = term.trim().lowercase()
            if (value.isEmpty()) emptyList()
            else Regex("(?<![\\p{L}\\p{N}])${Regex.escape(value)}(?![\\p{L}\\p{N}])").findAll(normalized)
                .map { it.range.first to it.range.last + 1 }
                .toList()
        }
    }

    private fun isNegated(text: String, start: Int): Boolean {
        val normalized = text.lowercase()
        val clauseStart = listOf(
            normalized.lastIndexOf(',', start - 1),
            normalized.lastIndexOf(';', start - 1),
            normalized.lastIndexOf('.', start - 1),
            normalized.lastIndexOf('!', start - 1),
            normalized.lastIndexOf('?', start - 1),
            normalized.lastIndexOf('\n', start - 1)
        ).maxOrNull()?.plus(1) ?: 0
        val prefix = normalized.substring(clauseStart, start)
        if (boundary.find(prefix) != null) {
            val lastBoundary = boundary.findAll(prefix).last().range.last + 1
            return negativeMarker.containsMatchIn(prefix.substring(lastBoundary))
        }
        val words = prefix.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        return words.takeLast(4).any { it == "nao" || it == "não" || it == "sem" || it == "evite" }
    }
}
