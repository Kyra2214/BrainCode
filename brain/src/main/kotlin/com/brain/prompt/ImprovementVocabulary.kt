package com.brain.prompt

/** Vocabulário canônico usado para reconhecer pedidos de ajuste entre turnos. */
object ImprovementVocabulary {
    val radicals: List<String> = listOf(
        "melhor", "otimiz", "reformul", "aperfeiç", "aperfeic",
        "mud", "troc", "substitu", "adicion", "ajust", "corrig", "edit"
    )

    val regex: String = radicals.joinToString("|") { Regex.escape(it) }
}
