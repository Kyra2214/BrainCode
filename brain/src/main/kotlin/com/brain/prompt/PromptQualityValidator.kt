package com.brain.prompt

import java.util.Locale

data class PromptQualityScore(
    val total: Double,
    val criterios: Map<String, Double>,
    val pontosFracos: Set<String>
) {
    val abaixoDoPadrao: Boolean get() = total < LIMIAR_MINIMO
    companion object {
        /** Regra: só entregar ao usuário sem passar por melhoria quando o prompt já
         *  reflete 90%+ do que foi pedido. Abaixo disso, o executor tenta melhorar
         *  (local e depois IA) antes de responder — nunca entrega o primeiro rascunho
         *  sem checar. */
        const val LIMIAR_MINIMO = 0.90
    }
}

/**
 * Avalia um prompt gerado por múltiplos critérios independentes — nunca só
 * por tamanho de texto. Determinístico, sem API: a mesma entrada sempre
 * produz a mesma pontuação.
 */
object PromptQualityValidator {

    fun validar(pedido: String, promptGerado: String, dominio: PromptDomain): PromptQualityScore {
        val texto = promptGerado.trim()
        if (texto.isBlank()) return PromptQualityScore(0.0, emptyMap(), setOf("clareza", "presença de elementos"))

        val fidelidade = fidelidade(pedido, texto)
        val presencaElementos = presencaElementos(pedido, texto, dominio)
        val clareza = clareza(texto)
        val especificidade = especificidade(texto)
        val coerencia = coerencia(texto)
        val estrutura = estrutura(texto, dominio)

        val criterios = mapOf(
            "fidelidade" to fidelidade,
            "presença de elementos" to presencaElementos,
            "clareza" to clareza,
            "especificidade" to especificidade,
            "coerência" to coerencia,
            "estrutura" to estrutura
        )
        val total = criterios.values.average()
        val pontosFracos = criterios.filterValues { it < 0.6 }.keys
        return PromptQualityScore(total, criterios, pontosFracos)
    }

    private fun fidelidade(pedido: String, texto: String): Double = PromptSimilarity.contentSimilarity(pedido, texto).coerceIn(0.0, 1.0).let {
        // contentSimilarity é estrita (interseção/união); um prompt bem escrito usa sinônimos e
        // frases completas, então uma sobreposição de tokens moderada já indica boa fidelidade.
        (it * 2.5).coerceIn(0.0, 1.0)
    }

    private fun presencaElementos(pedido: String, texto: String, dominio: PromptDomain): Double {
        if (dominio != PromptDomain.IMAGEM && dominio != PromptDomain.VIDEO) {
            return if (texto.length >= 20) 1.0 else 0.4
        }
        val esperados = listOf("ambient", "composi", "ilumina", "câmera", "camera", "lente", "profundidade", "realis", "qualidade", "estilo", "resolução")
        val presentes = esperados.count { it in texto.lowercase(Locale.ROOT) }
        return (presentes.toDouble() / 6.0).coerceIn(0.0, 1.0)
    }

    private fun clareza(texto: String): Double {
        val frases = texto.split(Regex("[.!?\\n]")).map { it.trim() }.filter { it.isNotBlank() }
        if (frases.isEmpty()) return 0.0
        val frasesRazoaveis = frases.count { it.length in 8..280 }
        return (frasesRazoaveis.toDouble() / frases.size).coerceIn(0.0, 1.0)
    }

    private fun especificidade(texto: String): Double {
        // PT-BR e inglês juntos — o pedido/prompt gerado pode chegar em qualquer um dos dois,
        // e uma lista só-português deixava termos vagos em inglês completamente sem penalidade.
        val vago = listOf(
            "algo", "qualquer coisa", "bonito", "legal", "bom", "interessante", "etc",
            "something", "anything", "nice", "cool", "good", "interesting", "stuff", "etc."
        )
        val termosVagos = vago.count { it in texto.lowercase(Locale.ROOT) }
        val palavras = texto.split(Regex("\\s+")).filter { it.isNotBlank() }
        val densidadeDetalhe = palavras.count { it.length > 6 }.toDouble() / palavras.size.coerceAtLeast(1)
        val penalidade = (termosVagos * 0.25)
        return (densidadeDetalhe * 1.6 - penalidade).coerceIn(0.0, 1.0)
    }

    private fun coerencia(texto: String): Double {
        val contraditorios = listOf(
            "dia" to "noite", "realista" to "cartoon", "colorido" to "preto e branco",
            "minimalista" to "detalhes excessivos", "interior" to "ao ar livre",
            "day" to "night", "colorful" to "black and white", "indoor" to "outdoor",
            "realistic" to "cartoon", "minimalist" to "excessive detail"
        )
        val lower = texto.lowercase(Locale.ROOT)
        val conflitos = contraditorios.count { (a, b) -> a in lower && b in lower }
        return (1.0 - conflitos * 0.4).coerceIn(0.0, 1.0)
    }

    private fun estrutura(texto: String, dominio: PromptDomain): Double = when (dominio) {
        PromptDomain.TEXTO, PromptDomain.CODIGO -> if (texto.lines().size >= 2) 1.0 else 0.5
        PromptDomain.IMAGEM, PromptDomain.VIDEO -> if (texto.contains(":")) 1.0 else 0.6
    }
}
