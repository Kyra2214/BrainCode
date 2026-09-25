package com.brain.research

import java.util.Locale

/**
 * Mede o quanto um texto (snippet de busca ou página real) realmente contém
 * termos da pergunta original.
 *
 * Existe porque `ResearchResult.confidence` tem default 0.0 e, até este
 * ponto, nenhum `WebResearchProvider` real o preenchia — então
 * `WebResearchAgent.quality()` sempre calculava a média sobre zeros e o
 * resultado nunca passava de `SourceQuality.LOW`, independentemente do
 * conteúdo. Esta classe dá ao harness um sinal de qualidade que reflete o
 * conteúdo de verdade, sem depender de heurísticas dentro de cada provider.
 *
 * Só é aplicada quando o provider não assume esse papel sozinho (ver
 * `WebResearchAgent`, que só recalcula quando `confidence == 0.0`) — um
 * provider que já calcula seu próprio confidence continua no controle.
 */
object ContentRelevanceScorer {
    /** Palavras interrogativas/conectivas comuns nas reescritas de `ResearchQueryRewriter` — não carregam sinal de tópico. */
    private val termosIgnorados = setOf(
        "como", "para", "sobre", "qual", "quais", "quando", "onde", "porque", "por que",
        "explicacao", "explique", "explanation", "definition", "context", "relevant",
        "facts", "architecture", "components", "implementation", "technical",
        "what", "which", "when", "where", "does", "with", "from", "that", "this",
        // Termos de enquadramento não distinguem a fonte da pergunta real.
        // Sem removê-los, consultas como "tempo em Macaé hoje" viram 1/3
        // de relevância mesmo quando a página contém a previsão correta.
        "tempo", "clima", "temperatura", "previsao", "previsão", "hoje", "agora",
        "amanha", "amanhã", "atual" , "momento"
    )

    /**
     * @return fração (0.0–1.0) dos termos significativos da consulta (>=4 letras,
     * fora da lista de conectivos) que aparecem no conteúdo. 0.0 se não houver
     * termo significativo na consulta ou o conteúdo estiver vazio.
     */
    fun score(content: String, query: String): Double {
        val termos = query.lowercase(Locale.ROOT)
            .split(Regex("[^\\p{L}\\p{Nd}]+"))
            .filter { it.length >= 4 && it !in termosIgnorados }
            .toSet()
        if (termos.isEmpty() || content.isBlank()) return 0.0
        val textoNormalizado = content.lowercase(Locale.ROOT)
        val acertos = termos.count { textoNormalizado.contains(it) }
        return (acertos.toDouble() / termos.size).coerceIn(0.0, 1.0)
    }
}
