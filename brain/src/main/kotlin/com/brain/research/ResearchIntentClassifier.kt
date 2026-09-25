package com.brain.research

/** Heurística local compartilhada por Secretário e WebResearch; nunca consulta modelo/LLM. */
enum class ResearchIntentKind { FACTUAL, TECHNICAL, ACADEMIC, GENERAL }

object ResearchIntentClassifier {
    fun classify(query: String): ResearchIntentKind {
        val text = query.lowercase()
        return when {
            Regex("(?i)\\b(paper|papers|artigo(s)? científico(s)?|estudo(s)? acadêmico(s)?|literatura científica|doi)\\b").containsMatchIn(text) -> ResearchIntentKind.ACADEMIC
            Regex("(?i)\\b(ktor|cors|gradle|kotlin|android|stack trace|exception|api rest|programação|programacao|código|codigo|bug|framework|compiler|compilador)\\b").containsMatchIn(text) || Regex("(?i)\\b(como configurar|como implementar|erro ao|como corrigir)\\b").containsMatchIn(text) -> ResearchIntentKind.TECHNICAL
            Regex("(?i)\\b(quando nasceu|onde nasceu|data de nascimento|born|birth|quem foi|quem é|quem e|qual a capital|capital de|população de|populacao de|quantos habitantes|data de fundação|data de fundacao)\\b").containsMatchIn(text) -> ResearchIntentKind.FACTUAL
            else -> ResearchIntentKind.GENERAL
        }
    }

    fun requiresWebSearch(query: String): Boolean = classify(query) != ResearchIntentKind.GENERAL
}
