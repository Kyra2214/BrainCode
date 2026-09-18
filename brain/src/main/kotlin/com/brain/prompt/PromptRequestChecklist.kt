package com.brain.prompt

import java.util.Locale

/** Resultado do checklist executado antes de compor um prompt. */
data class PromptRequestAnalysis(
    val tipo: PromptRequestType,
    val elementosFaltantes: Set<String> = emptySet(),
    val perguntas: List<String> = emptyList(),
    val avisos: List<String> = emptyList(),
    val pronto: Boolean = perguntas.isEmpty()
)

enum class PromptRequestType { CRIACAO, MELHORIA, TEXTO, CODIGO }

data class PromptGeneratedChecklist(
    val erros: List<String> = emptyList(),
    val avisos: List<String> = emptyList(),
    val coerente: Boolean = erros.isEmpty()
)

/**
 * Checklist local, barato e reproduzível. Ele não inventa conteúdo nem chama rede:
 * apenas impede que uma solicitação incompleta seja apresentada como prompt pronto.
 */
object PromptRequestChecklist {
    fun analisar(pedido: String): PromptRequestAnalysis {
        val texto = pedido.trim()
        val lower = texto.lowercase(Locale.ROOT)
        val dominio = PromptDomain.classificar(texto)
        val melhoria = ImprovementVocabulary.radicals.any { it in lower }
        if (melhoria) {
            return PromptRequestAnalysis(
                tipo = PromptRequestType.MELHORIA,
                elementosFaltantes = setOf("referência anterior"),
                perguntas = listOf("Qual é o prompt ou a imagem anterior que você quer melhorar?"),
                pronto = false
            )
        }
        if (dominio == PromptDomain.IMAGEM || dominio == PromptDomain.VIDEO) {
            val semMeta = lower
                .replace(Regex("(?i)\\b(?:crie|criar|gere|gerar|faça|fazer|faca|quero|preciso)\\b"), " ")
                .replace(Regex("\\bprompt\\b|\\bimagem\\b|\\bimagens\\b|\\bfoto\\b|\\bfotografia\\b|\\bvídeo\\b|\\bvideo\\b"), " ")
            val semCategoriaGenerica = lower.replace(Regex("(?i)\\b(?:um|uma)\\s+(?:imagem|imagens|foto|fotografia|vídeo|video)\\b"), " ")
            val temSujeito = Regex("(?i)\\b(?:de|para)\\s+(?:um|uma|o|a)\\s+[\\p{L}\\p{Nd}][^,.;!?]*").containsMatchIn(semCategoriaGenerica) ||
                semMeta.split(Regex("[^\\p{L}\\p{Nd}]+" )).count { it.length >= 5 } >= 2
            if (!temSujeito) {
                return PromptRequestAnalysis(
                    tipo = PromptRequestType.CRIACAO,
                    elementosFaltantes = setOf("sujeito principal"),
                    perguntas = listOf("Qual é o sujeito principal da imagem?"),
                    avisos = listOf("O estilo e o ambiente podem ser definidos depois."),
                    pronto = false
                )
            }
        }
        val tipo = when (dominio) {
            PromptDomain.CODIGO -> PromptRequestType.CODIGO
            PromptDomain.TEXTO -> PromptRequestType.TEXTO
            else -> PromptRequestType.CRIACAO
        }
        return PromptRequestAnalysis(tipo = tipo)
    }

    fun validarGerado(pedido: String, gerado: String): PromptGeneratedChecklist {
        val texto = gerado.trim()
        if (texto.isBlank()) return PromptGeneratedChecklist(listOf("prompt vazio"), coerente = false)
        val lower = texto.lowercase(Locale.ROOT)
        val avisos = mutableListOf<String>()
        val erros = mutableListOf<String>()
        val repeticoes = Regex("(?i)\\b([\\p{L}\\p{Nd}]+(?:\\s+[\\p{L}\\p{Nd}]+){2,})\\b.*\\b\\1\\b")
        if (repeticoes.containsMatchIn(texto)) avisos += "redundância detectada"

        val dominio = PromptDomain.classificar(pedido)
        if (dominio == PromptDomain.IMAGEM || dominio == PromptDomain.VIDEO) {
            val termosConcretos = pedido.lowercase(Locale.ROOT)
                .split(Regex("[^\\p{L}\\p{Nd}]+"))
                .filter { it.length >= 5 }
                .filterNot { it in setOf("criar", "crie", "gerar", "gere", "prompt", "imagem", "fotografia", "foto", "transforme", "transformar") }
            if (termosConcretos.isNotEmpty() && termosConcretos.none { it in lower }) {
                erros += "o prompt gerado não preserva o assunto ou a alteração principal solicitada"
            }
        }
        return PromptGeneratedChecklist(erros, avisos, erros.isEmpty())
    }
}
