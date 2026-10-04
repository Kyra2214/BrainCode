package com.brain.legado

/**
 * Regras históricas de domínio mantidas apenas para proveniência e migração.
 *
 * Este arquivo não participa do roteamento de produção. Clima, pesquisa,
 * esporte e demais intenções semânticas devem ser decididos pelo modelo e
 * executados através das tools autorizadas, não por listas de palavras.
 */
@Deprecated("Legado: não usar para classificar ou rotear requisições")
internal object LegacySemanticLexicon {
    val researchVerbs = listOf(
        "pesquisar", "pesquise", "pesquisa", "pesquisando", "procurar", "procure", "procura",
        "buscar", "busque", "busca", "verificar", "verifique", "confira", "conferir",
        "checar", "cheque", "consultar", "consulte", "consulta", "atualizar", "atualize",
        "descobrir", "descubra", "investigar", "investigue", "analisar", "analise", "comparar",
        "compare", "encontrar", "encontre", "documentar", "documente", "rastrear", "rastreie",
        "monitorar", "monitore", "acompanhar", "acompanhe", "avaliar", "avalie", "me diga",
        "me fala", "me conta", "mostrar", "mostre"
    )

    val interrogatives = listOf(
        "qual", "quais", "quanto", "quanta", "quantos", "quantas", "quando", "onde",
        "aonde", "quem", "por que", "por quê", "porque", "como está", "como esta"
    )

    val realtimeTopics = listOf(
        "temperatura", "clima", "previsão do tempo", "previsao do tempo", "tempo hoje",
        "tempo agora", "tempo em", "clima em", "chuva", "vai chover", "umidade", "vento",
        "cotação", "cotacao", "dólar", "dolar", "euro", "bitcoin", "placar",
        "resultado do jogo", "campeonato", "notícia", "noticia", "manchete", "trânsito",
        "transito", "voo", "status do voo", "hoje", "agora", "atual", "recente"
    )

    val weatherTopics = listOf(
        "temperatura", "clima", "tempo vai fazer", "previsão do tempo", "previsao do tempo",
        "tempo hoje", "tempo agora", "tempo em", "clima em", "vai chover", "chuva", "sol",
        "umidade", "vento"
    )

    val realtimeWithoutInterrogative = listOf("tempo hoje", "tempo agora", "tempo em", "clima em", "temperatura em")
}
