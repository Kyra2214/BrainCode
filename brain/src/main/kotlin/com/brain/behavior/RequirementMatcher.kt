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

    fun isPresent(requirement: String, result: String): Boolean {
        val normalized = requirement.trim().lowercase(java.util.Locale.ROOT)
        val lower = result.lowercase(java.util.Locale.ROOT)
        if (normalized.isBlank() || normalized in lower) return true

        val tokens = normalized
            .split(Regex("[^\\p{L}\\p{Nd}]+"))
            .filter { it.length >= 4 && it !in ignoredTokens }
        return tokens.isNotEmpty() && tokens.all { it in lower }
    }
}

fun requirementPresent(requirement: String, result: String): Boolean =
    RequirementMatcher.isPresent(requirement, result)
