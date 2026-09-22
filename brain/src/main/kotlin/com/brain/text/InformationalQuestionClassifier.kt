package com.brain.text

/** Classificação única e determinística de perguntas informacionais recuperáveis. */
object InformationalQuestionClassifier {
    fun isRecoverable(text: String): Boolean {
        val normalized = text.lowercase().trim().replace(Regex("\\s+"), " ")
        if (normalized.isBlank()) return false
        val asks = normalized.contains("?") || normalized.startsWith("qual ") || normalized.startsWith("quais ") ||
            normalized.startsWith("como ") || normalized.startsWith("o que ") || normalized.startsWith("quem ") ||
            normalized.startsWith("explique") || normalized.startsWith("explica") || normalized.contains(" explique ") ||
            normalized.contains(" explica ") || normalized.startsWith("fale sobre ") ||
            normalized.startsWith("falar sobre ") || normalized.startsWith("descreva ")
        val explanatory = normalized.contains("explic") || normalized.contains("como funciona") || normalized.contains("o que é") ||
            normalized.contains("o que e") || normalized.contains("qual a melhor") || normalized.contains("qual o melhor") ||
            normalized.contains("quais tecnologias") || normalized.contains("que tecnologias") || normalized.startsWith("fale sobre ") ||
            normalized.startsWith("falar sobre ") || normalized.startsWith("descreva ")
        return asks && explanatory
    }
}
