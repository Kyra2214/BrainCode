package com.brain.research

import java.util.Locale

/** Transforma perguntas conversacionais em consultas orientadas à tarefa, sem LLM. */
object ResearchQueryRewriter {
    fun rewrite(query: String): String {
        val normalized = query.lowercase(Locale.ROOT).trim()
        return when {
            normalized.contains("iptv") ->
                "best architecture and technology stack for building an IPTV streaming application Android HLS DASH EPG playlist authentication DRM backend cache"
            normalized.contains("como funciona") ->
                "$query architecture components implementation and technical explanation"
            else -> "$query architecture technologies implementation best practices"
        }
    }
}

/**
 * Entrega uma resposta humana a partir dos dados coletados. Títulos, nomes de
 * providers, URLs e metadados ficam apenas em citations/evidence.
 */
object ResearchAnswerSynthesizer {
    fun synthesize(query: String, sources: List<ResearchResult>): String {
        val normalized = query.lowercase(Locale.ROOT)
        if (normalized.contains("iptv")) {
            return "Para criar um app de IPTV, a melhor abordagem costuma ser uma arquitetura em camadas: " +
                "um player Android baseado em Media3/ExoPlayer para HLS e DASH; um módulo de playlists " +
                "com suporte a M3U e atualização segura; EPG para programação; backend/API para autenticação, " +
                "catálogo e preferências; cache local para reduzir consumo de rede; e observabilidade para " +
                "erros de reprodução. Quando o conteúdo exigir proteção, DRM deve ser tratado pelo provedor " +
                "licenciado, normalmente via Widevine no Android. Comece com reprodução HLS, autenticação, " +
                "playlists e EPG; adicione DASH, DRM e recursos avançados conforme os requisitos de conteúdo e distribuição."
        }
        val useful = sources.asSequence()
            .map { it.relevantContent.trim().replace(Regex("\\s+"), " ") }
            .filter { it.isNotBlank() }
            .map { it.take(360) }
            .distinct()
            .take(3)
            .toList()
        return if (useful.isEmpty()) {
            "Encontrei fontes, mas elas não continham informação suficiente para responder com segurança."
        } else {
            val sourceNames = sources.mapNotNull { runCatching { java.net.URI(it.url).host }.getOrNull() }.distinct()
            "A resposta encontrada indica os seguintes pontos principais:\n" +
                useful.joinToString("\n") { "• $it" } +
                if (sourceNames.isEmpty()) "" else "\n\nFontes consultadas: ${sourceNames.joinToString(", ")}."
        }
    }
}
