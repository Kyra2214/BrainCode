package com.brain.prompt

import java.util.Locale

/**
 * Estratégia local determinística de similaridade/compatibilidade.
 *
 * A implementação atual é lexical e não depende de API. O contrato fica
 * isolado para que um scorer semântico local possa substituir esta estratégia
 * sem alterar o fluxo da biblioteca, planner ou executor.
 */
object PromptSimilarity {
    fun compatibility(request: String, template: PromptTemplate): Double {
        val requested = tokenize(request)
        if (requested.isEmpty()) return 0.0
        val metadata = tokenize("${template.finalidade} ${template.contextoDeUso} ${template.skillRelacionada.orEmpty()}")
        if (metadata.isEmpty()) return 0.0
        val overlap = requested.count { token -> metadata.any { meta -> meta == token || meta.contains(token) || token.contains(meta) } }
        val requestCoverage = overlap.toDouble() / requested.size
        val metadataCoverage = overlap.toDouble() / metadata.size.coerceAtLeast(1)
        return (requestCoverage * 0.75 + metadataCoverage * 0.25).coerceIn(0.0, 1.0)
    }

    fun contentSimilarity(left: String, right: String): Double {
        val a = tokenize(left); val b = tokenize(right)
        if (a.isEmpty() || b.isEmpty()) return 0.0
        return a.intersect(b).size.toDouble() / a.union(b).size
    }

    fun tokenize(text: String): Set<String> = text.lowercase(Locale.ROOT)
        .split(Regex("[^\\p{L}\\p{N}]+"))
        .filter { it.length > 2 }
        .toSet()
}
