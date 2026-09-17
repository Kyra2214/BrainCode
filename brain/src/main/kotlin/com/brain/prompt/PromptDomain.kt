package com.brain.prompt

/** Domínio detectado do pedido de prompt — decide template/estrutura de composição local. */
enum class PromptDomain {
    IMAGEM,
    VIDEO,
    TEXTO,
    CODIGO;

    companion object {
        /** Classificação determinística por intenção semântica e evidência textual — sem API. */
        fun classificar(pedido: String): PromptDomain {
            val texto = pedido.lowercase()
            val querImagem = texto.containsAny(
                "imagem", "imagens", "foto", "fotografia", "fotografias", "fotorrealista", "foto-realista",
                "ilustração", "ilustracao", "arte digital", "pintura", "retrato", "desenho", "render",
                "wallpaper", "cartaz", "pôster", "poster", "capa", "criar uma imagem", "criar imagem",
                "gerar uma imagem", "gerar imagem", "crie uma imagem", "crie imagem", "fazer uma imagem",
                "fazer imagem"
            )
            val querVideo = texto.containsAny("vídeo", "video", "clipe", "animação", "animacao", "cena em movimento", "storyboard")
            val querCodigo = texto.containsAny(
                "código", "codigo", "função", "funcao", "classe", "script", "programa", "algoritmo",
                "endpoint", "api rest", "implementar", "refatorar", "bug", "compilar", "app", "aplicativo",
                "interface", "tela", "layout", "android", "ios", "chatbox", "frontend", "backend"
            )
            val textoGenerico = texto.containsAny(
                "resumo", "resumo executivo", "reunião", "reuniao", "relatório", "relatorio",
                "documento", "artigo", "ata", "email", "e-mail", "mensagem", "texto",
                "explicação", "explicacao", "análise", "analise", "síntese", "sintese"
            )
            return when {
                querVideo && !querImagem -> VIDEO
                querImagem && !querCodigo -> IMAGEM
                querCodigo && !querImagem -> CODIGO
                querImagem -> IMAGEM
                !textoGenerico && texto.containsAny(
                    " prompt de uma ", " prompt de um ", " prompt para uma ", " prompt para um ",
                    " prompt para criar ", " prompt para gerar ", " prompt para fazer ", " prompt de imagem "
                ) -> IMAGEM
                else -> TEXTO
            }
        }

        private fun String.containsAny(vararg termos: String) = termos.any { it in this }
    }
}
