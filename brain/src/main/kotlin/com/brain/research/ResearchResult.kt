package com.brain.research

import java.time.Instant

/**
 * Um resultado individual de pesquisa web — sempre rastreável até uma fonte
 * real. Nunca é criado sem `url`/`source` vindos de uma resposta real do
 * provedor de busca.
 */
data class ResearchResult(
    val query: String,
    val source: String,
    val title: String,
    val url: String,
    val relevantContent: String,
    val retrievedAt: Instant
)

/** Contrato da capacidade de pesquisa — implementações reais ficam no módulo Android (I/O de rede). */
fun interface WebResearchProvider {
    /** @return sucesso com 0+ resultados, ou falha explicando por que a pesquisa não pôde ser feita. */
    fun pesquisar(query: String, maxResultados: Int): Result<List<ResearchResult>>
}

/** Atalho público que preserva o limite padrão sem violar o contrato SAM da interface. */
fun WebResearchProvider.pesquisar(query: String): Result<List<ResearchResult>> = pesquisar(query, 3)

/**
 * Sanitiza a consulta antes de qualquer chamada externa — remove segredos,
 * chaves de API, tokens e caminhos locais do workspace. Determinístico.
 */
object QuerySanitizer {
    private val padroesSensiveis = listOf(
        Regex("(?i)(api[_-]?key|token|secret|senha|password|bearer)\\s*[:=]\\s*\\S+"),
        Regex("sk-[A-Za-z0-9]{10,}"),
        Regex("(?i)bearer\\s+[A-Za-z0-9._-]{10,}"),
        Regex("[A-Za-z0-9+/]{32,}={0,2}"), // blobs longos tipo base64 (chaves/tokens)
        Regex("(?i)/(home|data|storage)/[\\w./-]+") // caminhos locais do dispositivo/workspace
    )

    fun sanitizar(query: String): String {
        var limpo = query
        padroesSensiveis.forEach { padrao -> limpo = padrao.replace(limpo, "") }
        return limpo.replace(Regex("\\s{2,}"), " ").trim()
    }
}
