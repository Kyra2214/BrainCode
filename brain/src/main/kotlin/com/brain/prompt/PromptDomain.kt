package com.brain.prompt

import com.brain.text.TermMatcher

/** Domínio detectado do pedido de prompt — decide template/estrutura de composição local. */
enum class PromptDomain {
    IMAGEM,
    VIDEO,
    TEXTO,
    CODIGO;

    companion object {
        val visualTerms: Set<String> = setOf(
            "imagem", "imagens", "foto", "fotografia", "fotografias", "fotorrealista", "foto-realista",
            "ilustração", "ilustracao", "arte digital", "pintura", "retrato", "desenho", "render",
            "wallpaper", "cartaz", "pôster", "poster", "capa"
        )

        fun isVisualRequest(request: String): Boolean =
            TermMatcher.containsAnyWhole(request, visualTerms) ||
                TermMatcher.containsAnyWhole(request, "criar uma imagem", "criar imagem", "gerar uma imagem", "gerar imagem", "crie uma imagem", "crie imagem", "fazer uma imagem", "fazer imagem")

        /** Classificação determinística por intenção semântica e evidência textual — sem API. */
        fun classificar(pedido: String): PromptDomain {
            val texto = pedido.lowercase()
            val querImagem = isVisualRequest(texto)
            val querVideo = TermMatcher.containsAnyWhole(texto, "vídeo", "video", "clipe", "animação", "animacao", "cena em movimento", "storyboard")
            val querCodigo = TermMatcher.containsAnyWhole(texto,
                "código", "codigo", "função", "funcao", "classe", "script", "programa", "algoritmo",
                "endpoint", "api rest", "implementar", "refatorar", "bug", "compilar", "app", "aplicativo",
                "interface", "tela", "layout", "android", "ios", "chatbox", "frontend", "backend"
            )
            val textoGenerico = TermMatcher.containsAnyWhole(texto,
                "resumo", "resumo executivo", "reunião", "reuniao", "relatório", "relatorio",
                "documento", "artigo", "ata", "email", "e-mail", "mensagem", "texto",
                "explicação", "explicacao", "análise", "analise", "síntese", "sintese"
            )
            return when {
                querVideo && !querImagem -> VIDEO
                querImagem && !querCodigo -> IMAGEM
                querCodigo && !querImagem -> CODIGO
                querImagem -> IMAGEM
                !textoGenerico && TermMatcher.containsAnyWhole(texto,
                    " prompt de uma ", " prompt de um ", " prompt para uma ", " prompt para um ",
                    " prompt para criar ", " prompt para gerar ", " prompt para fazer ", " prompt de imagem "
                ) -> IMAGEM
                else -> TEXTO
            }
        }
    }
}
