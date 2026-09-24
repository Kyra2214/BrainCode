package com.brain.research

/**
 * Encadeia múltiplos [WebResearchProvider] com fallback real: tenta o primeiro,
 * e só cai para o próximo quando o anterior falha (rede, timeout, parsing) OU
 * retorna 0 resultados — nunca porque "pareceu melhor". Isso evita que uma
 * mudança de marcação HTML numa única fonte (ex.: scraping do DuckDuckGo)
 * derrube silenciosamente toda a capacidade de pesquisa.
 *
 * Falha só quando TODA fonte falhar — nesse caso preserva o motivo da última
 * tentativa (mais informativo para quem lê o evidence) mas anota, na
 * mensagem, quantas fontes foram tentadas.
 */
class CompositeWebResearchProvider(
    private val providers: List<WebResearchProvider>
) : WebResearchProvider {
    init {
        require(providers.isNotEmpty()) { "CompositeWebResearchProvider precisa de ao menos um provider" }
    }

    override fun pesquisar(query: String, maxResultados: Int): Result<List<ResearchResult>> {
        var ultimaFalha: Throwable? = null
        for (provider in providers) {
            val outcome = runCatching { provider.pesquisar(query, maxResultados) }.fold(
                onSuccess = { it },
                onFailure = { Result.failure(it) }
            )
            outcome.onSuccess { resultados -> if (resultados.isNotEmpty()) return Result.success(resultados) }
            outcome.onFailure { ultimaFalha = it }
        }
        return Result.failure(
            ultimaFalha ?: IllegalStateException("nenhuma fonte de pesquisa retornou resultados (${providers.size} tentada(s))")
        )
    }
}
