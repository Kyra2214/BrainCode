package com.sandbox.app

/** Decodifica texto de snippets sem permitir entidades HTML na camada conversacional. */
internal object HtmlTextDecoder {
    private val numeric = Regex("&#(?:x([0-9a-fA-F]+)|(\\d+));")

    fun decode(fragment: String): String {
        var decoded = fragment.replace(Regex("<[^>]+>"), "")
        val named = mapOf("&amp;" to "&", "&quot;" to "\"", "&#39;" to "'", "&#x27;" to "'", "&nbsp;" to " ", "&lt;" to "<", "&gt;" to ">", "&apos;" to "'")
        named.forEach { (entity, value) -> decoded = decoded.replace(entity, value, ignoreCase = true) }
        decoded = numeric.replace(decoded) { match ->
            val hex = match.groupValues[1]
            val digits = if (hex.isNotBlank()) hex else match.groupValues[2]
            val value = digits.toIntOrNull(if (hex.isNotBlank()) 16 else 10)
            value?.toChar()?.toString() ?: match.value
        }
        return decoded.trim()
    }
}
