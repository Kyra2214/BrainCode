package com.brain.prompt

/** Domínio detectado do pedido de prompt — decide template/estrutura de composição local. */
enum class PromptDomain {
    IMAGEM,
    VIDEO,
    TEXTO,
    CODIGO;

    companion object {
        /** Classificação determinística por palavras-chave — sem API, sem ambiguidade custosa. */
        fun classificar(pedido: String): PromptDomain {
            val texto = pedido.lowercase()
            return when {
                texto.containsAny("vídeo", "video", "clipe", "animação", "animacao", "cena em movimento", "storyboard") -> VIDEO
                texto.containsAny(
                    "código", "codigo", "função", "funcao", "classe", "script", "programa", "algoritmo",
                    "endpoint", "api rest", "implementar", "refatorar", "bug", "compilar", "app", "aplicativo",
                    "interface", "tela", "layout", "android", "ios", "chatbox", "frontend", "backend"
                ) -> CODIGO
                texto.containsAny(
                    "imagem", "foto", "fotografia", "fotorrealista", "foto-realista", "ilustra", "arte digital",
                    "pintura", "retrato", "desenho", "render", "wallpaper", "cartaz", "pôster", "poster", "capa"
                ) || (
                    "prompt" in texto &&
                        texto.containsAny(" prompt de uma ", " prompt de um ", " prompt para uma ", " prompt para um ") &&
                        !texto.containsAny("código", "codigo", "program", "script", "texto", "resumo", "app", "aplicativo", "interface", "tela", "layout")
                    ) -> IMAGEM
                else -> TEXTO
            }
        }

        private fun String.containsAny(vararg termos: String) = termos.any { it in this }
    }
}
