package com.brain.text

/** Classificação única e determinística de perguntas informacionais recuperáveis. */
object InformationalQuestionClassifier {
    fun isRecoverable(text: String): Boolean {
        val normalized = text.lowercase().trim().replace(Regex("\\s+"), " ")
        if (normalized.isBlank()) return false
        if (Regex("^(oi|olá|ola)(?:\\b|[,!?.])") .containsMatchIn(normalized) ||
            Regex("^(bom dia|boa tarde|boa noite|obrigado|obrigada|valeu)[!. ]*$").matches(normalized)) return false
        if (normalized.startsWith("/")) return false
        // A recuperação depende de localMiss + fallback disponível, não de uma
        // forma fixa de pergunta. Aqui ficam apenas exclusões explícitas.
        return !Regex("(?i)\\b(apenas converse|apenas conversar|só conversar|so conversar|somente conversar|não pesquise|nao pesquise|sem pesquisa|no web)\\b").containsMatchIn(normalized)
    }
}
