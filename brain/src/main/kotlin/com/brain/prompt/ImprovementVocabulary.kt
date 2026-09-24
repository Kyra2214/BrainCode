package com.brain.prompt

/** Vocabulário canônico usado para reconhecer pedidos de ajuste entre turnos. */
object ImprovementVocabulary {
    val radicals: List<String> = listOf(
        "melhor", "otimiz", "reformul", "aperfeiç", "aperfeic",
        "mud", "troc", "substitu", "adicion", "ajust", "corrig", "edit",
        "profission", "coloc", "insir", "inser", "acrescent",
        "refaç", "refac", "refaz", "aprimor", "refin", "reescrev", "caprich"
    )

    val regex: String = radicals.joinToString("|") { Regex.escape(it) }

    fun containsIn(text: String): Boolean = radicals.any { it in text.lowercase() }

    /**
     * Gatilho para acionar a IA (API) na melhoria de um prompt: "melhore ele", "faça melhor", "refaça",
     * "capriche", "de novo", "outra versão"... Um ajuste simples ("quero ele num deserto") continua local
     * e só escala para a IA se a qualidade local for insuficiente (princípio: IA no mínimo necessário).
     */
    private val aiTrigger = Regex(
        "\\b(?:melhor|otimiz|refa[çc]|refaz|refin|aprimor|reescrev|reformul|caprich|aperfei[çc]o)" +
            "|\\b(?:faz|faça|faca|fazer|fa[çc]a)\\s+(?:algo\\s+|isso\\s+|ele\\s+|ela\\s+)?melhor" +
            "|\\bde\\s+novo|\\boutra\\s+vers[ãa]o|\\bnova\\s+vers[ãa]o|\\bmais\\s+(?:detalhad|profission|caprichad)"
    )

    fun pedeIA(text: String): Boolean = aiTrigger.containsMatchIn(text.lowercase())
}
