package com.brain.router

/**
 * Implementação mínima do AIRouter (Fase C). Adaptada do scoring
 * determinístico do LocalAIRouter do IaBrain (brain/LocalAIRouter.kt):
 * mesma ideia — pontuar cada candidato e ordenar por total ponderado —
 * mas aqui o candidato é ProviderModel e a pontuação depende do
 * LiveStats consultado no catalog NO MOMENTO da decisão, não de campos
 * fixos do candidato. Por isso dois pedidos idênticos podem escolher
 * provedores diferentes, como o comentário do stub original previa.
 *
 * Chave inválida é filtro quase-duro: um modelo com CHAVE_INVALIDA
 * registrada não passa a funcionar só por tentar de novo, então só é
 * escolhido se não sobrar mais nenhum outro candidato para o papel.
 */
class DefaultAIRouter(
    private val pesos: RoutingWeights = RoutingWeights()
) : AIRouter {

    override fun decidir(
        papel: PapelPipeline,
        catalog: ApiCatalog,
        profiles: List<RoutingProfile>
    ): RoutingDecision? {
        val candidatos = catalog.listarPorPapel(papel)
        if (candidatos.isEmpty()) return null

        val (utilizaveis, comChaveInvalida) = candidatos.partition { modelo ->
            catalog.statsAtuais(modelo.providerId, modelo.modeloId)?.ultimoErro?.tipo != TipoErro.CHAVE_INVALIDA
        }
        val pool = utilizaveis.ifEmpty { comChaveInvalida }
        if (pool.isEmpty()) return null

        val ranqueado = pool
            .map { modelo -> modelo to pontuar(modelo, catalog, profiles) }
            .sortedWith(compareByDescending<Pair<ProviderModel, Double>> { it.second }.thenBy { it.first.modeloId })

        val (escolhido, pontuacao) = ranqueado.first()
        val alternativas = ranqueado.drop(1).map { it.first }

        return RoutingDecision(
            escolhido = escolhido,
            alternativas = alternativas,
            motivo = motivoPara(
                escolhido = escolhido,
                pontuacao = pontuacao,
                catalog = catalog,
                usouFallbackDeChaveInvalida = pool === comChaveInvalida
            )
        )
    }

    override fun proximaAlternativa(decisaoAnterior: RoutingDecision): ProviderModel? =
        decisaoAnterior.alternativas.firstOrNull()

    private fun pontuar(modelo: ProviderModel, catalog: ApiCatalog, profiles: List<RoutingProfile>): Double {
        val perfil = profiles.find { it.providerId == modelo.providerId && it.modeloId == modelo.modeloId }
        val stats = catalog.statsAtuais(modelo.providerId, modelo.modeloId)

        val qualidade = perfil?.qualityScore ?: 0.5
        // Sem histórico ainda: neutro — não penaliza nem favorece um modelo
        // novo frente a um já testado, só a Memória (Fase D) muda isso com o tempo.
        val confiabilidade = stats?.taxaSucessoRecente ?: 0.5
        val velocidade = stats?.latenciaMediaMs
            ?.let { ms -> (1.0 - (ms / pesos.latenciaReferenciaMs)).coerceIn(0.0, 1.0) }
            ?: 0.5
        val quotaEsgotada = (stats?.quotaRestanteEstimada ?: 1) <= 0

        val penalidadeErro = when (stats?.ultimoErro?.tipo) {
            TipoErro.CHAVE_INVALIDA -> pesos.penalidadeChaveInvalida
            TipoErro.LIMITE_ATINGIDO -> pesos.penalidadeLimite
            TipoErro.ERRO_SERVIDOR, TipoErro.TIMEOUT -> pesos.penalidadeErroTransitorio
            TipoErro.POLICY_NEGADA, TipoErro.REQUISICAO_INVALIDA -> pesos.penalidadeChaveInvalida
            TipoErro.DESCONHECIDO -> pesos.penalidadeErroTransitorio / 2
            null -> 0.0
        }

        return (qualidade * pesos.qualidade) +
            (confiabilidade * pesos.confiabilidade) +
            (velocidade * pesos.velocidade) +
            (if (modelo.cost == com.brain.capability.CostClass.FREE) pesos.bonusFreeFirst else 0.0) +
            (if (quotaEsgotada) pesos.penalidadeQuotaEsgotada else 0.0) +
            penalidadeErro
    }

    private fun motivoPara(
        escolhido: ProviderModel,
        pontuacao: Double,
        catalog: ApiCatalog,
        usouFallbackDeChaveInvalida: Boolean
    ): String {
        val stats = catalog.statsAtuais(escolhido.providerId, escolhido.modeloId)
        return buildList {
            add("melhor pontuação entre os candidatos do papel (%.2f)".format(pontuacao))
            when {
                stats?.taxaSucessoRecente != null ->
                    add("taxa de sucesso recente %.0f%%".format(stats.taxaSucessoRecente * 100))
                else -> add("sem histórico ainda — decisão baseada só no qualityScore do profile")
            }
            if (usouFallbackDeChaveInvalida) {
                add("ATENÇÃO: todos os candidatos têm chave inválida registrada; escolhido mesmo assim por falta de alternativa")
            }
        }.joinToString("; ")
    }
}

/**
 * Pesos do scoring — mesma ideia do RoutingPolicy do LocalAIRouter (IaBrain),
 * reduzida às dimensões que ainda fazem sentido aqui. "Comando" e "capacidade
 * exigida" saíram porque isso já é resolvido antes de pontuar, pelo filtro de
 * PapelPipeline em catalog.listarPorPapel() — o que sobra pra decidir ENTRE
 * candidatos do mesmo papel é qualidade, confiabilidade, velocidade e erro.
 */
data class RoutingWeights(
    val qualidade: Double = 1.5,
    val confiabilidade: Double = 2.0,
    val velocidade: Double = 0.5,
    val latenciaReferenciaMs: Double = 10_000.0,
    val penalidadeQuotaEsgotada: Double = -5.0,
    val bonusFreeFirst: Double = 0.25,
    val penalidadeChaveInvalida: Double = -10.0,
    val penalidadeLimite: Double = -1.0,
    val penalidadeErroTransitorio: Double = -0.3
)
