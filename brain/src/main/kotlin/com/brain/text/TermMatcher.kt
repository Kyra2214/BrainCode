package com.brain.text

/** Matching determinístico com fronteira Unicode para evitar substring dentro de palavras. */
object TermMatcher {
    fun containsAnyWhole(text: String, terms: Iterable<String>): Boolean = terms.any { term ->
        containsWhole(text, term)
    }

    fun containsAnyWhole(text: String, vararg terms: String): Boolean = containsAnyWhole(text, terms.asIterable())

    fun containsAnyStem(text: String, terms: Iterable<String>): Boolean = terms.any { term ->
        val normalized = term.trim().lowercase()
        normalized.isNotEmpty() && Regex("(?<![\\p{L}\\p{N}])${Regex.escape(normalized)}").containsMatchIn(text.lowercase())
    }

    fun containsAnyStem(text: String, vararg terms: String): Boolean = containsAnyStem(text, terms.asIterable())

    private fun containsWhole(text: String, term: String): Boolean {
        val normalized = term.trim().lowercase()
        if (normalized.isEmpty()) return false
        return Regex("(?<![\\p{L}\\p{N}])${Regex.escape(normalized)}(?![\\p{L}\\p{N}])").containsMatchIn(text.lowercase())
    }
}
