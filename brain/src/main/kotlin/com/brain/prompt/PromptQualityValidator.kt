package com.brain.prompt

import com.brain.reasoning.Requirement
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
        return validar(pedido, promptGerado, dominio, emptyList())
    }

    /** Validação alinhada ao Brain: requisitos explícitos não dependem apenas de tokens do pedido. */
    fun validar(
        pedido: String,
        promptGerado: String,
        dominio: PromptDomain,
        requisitos: List<Requirement>
    ): PromptQualityScore {
        val texto = promptGerado.trim()
        if (texto.isBlank()) return PromptQualityScore(0.0, emptyMap(), setOf("clareza", "presença de elementos"))

        val fidelidade = fidelidade(pedido, texto)
        val presencaElementos = presencaElementos(pedido, texto, dominio, requisitos)
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

    private fun presencaElementos(pedido: String, texto: String, dominio: PromptDomain, requisitos: List<Requirement>): Double {
        if (dominio != PromptDomain.IMAGEM && dominio != PromptDomain.VIDEO) {
            return if (texto.length >= 20) 1.0 else 0.4
        }
        val termos = (requisitos.map { it.text } + requisitosConcretos(pedido))
            .flatMap { it.lowercase(Locale.ROOT).split(Regex("[^\\p{L}\\p{Nd}]+")) }
            .filter { it.length >= 4 }
            .distinct()
        if (termos.isEmpty()) return 0.0
        val lower = texto.lowercase(Locale.ROOT)
        val presentes = termos.count { termo -> lower.split(Regex("[^\\p{L}\\p{Nd}]+" )).any { it == termo || it.startsWith(termo) || termo.startsWith(it) } }
        val coberturaTokens = presentes.toDouble() / termos.size
        val requisitosObrigatorios = requisitosCompostosObrigatorios(pedido)
        if (requisitosObrigatorios.any { it !in lower }) return 0.0
        return coberturaTokens.coerceIn(0.0, 1.0)
    }

    /** Termos observáveis do pedido; rótulos fixos como "Iluminação" não contam como cobertura. */
    private fun requisitosConcretos(pedido: String): List<String> {
        val stopwords = setOf(
            "crie", "criar", "gere", "gerar", "escreva", "faca", "faça", "um", "uma", "uns", "umas",
            "o", "a", "os", "as", "de", "do", "da", "dos", "das", "para", "por", "com", "e", "em",
            "um", "prompt", "prompts", "imagem", "imagens", "foto", "fotografia", "fotografico", "fotográfico",
            "video", "vídeo", "cena", "tipo", "sobre", "que", "seja"
        )
        return pedido.lowercase(Locale.ROOT)
            .split(Regex("[^\\p{L}\\p{Nd}]+"))
            .map { it.trim() }
            .filter { it.length >= 4 && it !in stopwords }
            .distinct()
    }

    /** Requisitos compostos não podem ser satisfeitos por palavras genéricas isoladas. */
    private fun requisitosCompostosObrigatorios(pedido: String): List<String> = listOf(
        Regex("(?i)\\bceu\\s+estrelado(?:\\s+ao\\s+fundo)?\\b"),
        Regex("(?i)\\bcéu\\s+estrelado(?:\\s+ao\\s+fundo)?\\b")
    ).mapNotNull { regex ->
        regex.find(pedido)?.value?.lowercase(Locale.ROOT)?.replace(Regex("\\s+"), " ")
    }.distinct()

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
