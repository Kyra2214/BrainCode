package com.brain.planner

import com.brain.prompt.PromptDomain
import java.util.Locale

/**
 * Consulta de pesquisa para prompts visuais. A frase crua do usuário ("vamos melhorar ele quero ele num
 * deserto...") trazia palavras de conversa e trazia resultados como "Reformulador de Texto" e frases do
 * Pensador. Aqui a consulta vira: assunto do prompt anterior + o que foi pedido agora + termos técnicos
 * de imagem — determinístico, sem IA.
 */
object ResearchQuery {
    private val conversa = setOf(
        "crie", "criar", "gere", "gerar", "faça", "faca", "fazer", "escreva", "prompt", "prompts", "vamos",
        "melhorar", "melhore", "melhora", "refaça", "refaca", "refazer", "aprimore", "capriche", "reescreva",
        "otimize", "mude", "muda", "troque", "troca", "adicione", "coloque", "coloca", "quero", "queria", "gostaria",
        "ele", "ela", "isso", "esse", "essa", "este", "esta", "num", "numa", "uma", "uns", "umas", "com", "sem", "para",
        "por", "pelo", "pela", "que", "como", "mais", "muito", "bem", "imagem", "objetivo", "atual", "ainda", "também", "tambem"
    )
    private const val TERMOS_VISUAIS = "fotografia composição iluminação"

    fun paraPromptVisual(objetivo: String): String {
        val foco = objetivo.substringBefore("\nReferências resolvidas:")
            .removePrefix("Objetivo atual:")
            .substringBefore("\n")
            .trim()
        val artefato = objetivo.substringAfter("artefato anterior:", "")
        val assuntoAnterior = Regex("(?i)\\b(?:de|do|da)\\s+(?:um|uma|o|a)\\s+([^,.;]+)").find(artefato)?.groupValues?.get(1).orEmpty()
        val palavras = (assuntoAnterior + " " + foco)
            .lowercase(Locale.ROOT)
            .split(Regex("[^\\p{L}\\p{Nd}]+"))
            .filter { it.length >= 3 && it !in conversa }
            .distinct()
            .take(8)
        if (palavras.isEmpty()) return foco.ifBlank { objetivo.trim() }
        return (palavras + TERMOS_VISUAIS).joinToString(" ")
    }

    fun ehVisual(objetivo: String): Boolean = PromptDomain.classificar(objetivo) == PromptDomain.IMAGEM
}
