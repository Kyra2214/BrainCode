package com.brain.behavior

/**
 * Compara requisitos textuais sem tratar variações de apresentação como ausência
 * do requisito concreto. Ex.: "a imagem de um foguete decolando" e
 * "ilustração detalhada de um foguete decolando" expressam o mesmo sujeito.
 */
object RequirementMatcher {
    private val ignoredTokens = setOf(
        "a", "as", "o", "os", "um", "uma", "uns", "umas",
        "de", "do", "da", "dos", "das", "para", "por", "com", "em",
        "imagem", "imagens", "foto", "fotos", "fotografia", "fotografias",
        "ilustracao", "ilustracoes", "ilustração", "ilustrações",
        "figura", "figuras", "cena", "cenas", "visual", "visuais"
    )

    /** Minúsculas e sem acentos: "por do sol" (como o usuário digita) casa com "pôr do sol" (como o prompt escreve). */
    private fun fold(texto: String): String =
        java.text.Normalizer.normalize(texto.lowercase(java.util.Locale.ROOT), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")

    fun isPresent(requirement: String, result: String): Boolean {
        val normalized = fold(requirement.trim())
        val lower = fold(result)
        if (normalized.isBlank()) return true

        // Contratos como "interface/aplicativo" representam alternativas:
        // basta uma das opções estar presente no resultado.
        val alternatives = normalized.split(Regex("\\s*/\\s*"))
            .map { it.trim() }
            .filter { it.isNotBlank() }
        if (alternatives.size > 1) return alternatives.any { isPresent(it, result) }

        if (normalized in lower) return true

        val tokens = normalized
            .split(Regex("[^\\p{L}\\p{Nd}]+"))
            .filter { it.length >= 4 && it !in ignoredTokens }
        return tokens.isNotEmpty() && tokens.all { it in lower }
    }
}

fun requirementPresent(requirement: String, result: String): Boolean =
    RequirementMatcher.isPresent(requirement, result)
