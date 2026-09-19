package com.brain.prompt

/** Vocabulário canônico usado para reconhecer pedidos de ajuste entre turnos. */
object ImprovementVocabulary {
    val radicals: List<String> = listOf(
        "melhor", "otimiz", "reformul", "aperfeiç", "aperfeic",
        "mud", "troc", "substitu", "adicion", "ajust", "corrig", "edit",
        "profission", "coloc", "insir", "inser", "acrescent"
    )

    val regex: String = radicals.joinToString("|") { Regex.escape(it) }

    fun containsIn(text: String): Boolean = radicals.any { it in text.lowercase() }
}
