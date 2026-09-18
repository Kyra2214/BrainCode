package com.brain.router

import com.brain.capability.CostClass
import java.time.Instant

/**
 * Catálogo de APIs gratuitas — dinâmico, não números fixos.
 * Granularidade é por MODELO dentro de um provider, porque limite/latência/
 * sucesso variam por modelo, não só por empresa.
 */
data class ProviderModel(
    val providerId: String,
    val modeloId: String,
    val papeisSugeridos: List<PapelPipeline>,
    val janela: JanelaLimite,
    val contextoMaximoTokens: Int? = null,
    val cost: CostClass = CostClass.FREE
)

data class JanelaLimite(
    val porMinuto: Int? = null,
    val porDia: Int? = null,
    val tokensPorDia: Long? = null
)

enum class PapelPipeline { PLANEJAMENTO, PRODUCAO_DE_ARTEFATO, ESCRITA_DE_PROMPT, EXECUCAO_CODIGO }

/**
 * Estado observado em tempo real — isto é o que o roteador realmente
 * usa para decidir, não a janela estática declarada pelo provedor.
 */
data class LiveStats(
    val providerId: String,
    val modeloId: String,
    val quotaRestanteEstimada: Int?,
    val ultimoErro: ErroObservado?,
    val latenciaMediaMs: Long?,
    val taxaSucessoRecente: Double?,
    val atualizadoEm: Instant
)

data class ErroObservado(
    val tipo: TipoErro,
    val ocorridoEm: Instant
)

enum class TipoErro { LIMITE_ATINGIDO, TIMEOUT, ERRO_SERVIDOR, CHAVE_INVALIDA, POLICY_NEGADA, REQUISICAO_INVALIDA, DESCONHECIDO }

/**
 * Catálogo em runtime. O Android instala uma implementação dinâmica através
 * de ApiCatalogRegistry; testes/offline podem usar InMemoryApiCatalog.
 * A lista de modelos não é uma fonte permanente de verdade.
 */
interface ApiCatalog {
    fun listarModelos(): List<ProviderModel>
    fun listarPorPapel(papel: PapelPipeline): List<ProviderModel>
    fun statsAtuais(providerId: String, modeloId: String): LiveStats?

    /** Chamado após cada chamada real, sucesso ou falha — mantém o LiveStats vivo. */
    fun registrarResultado(providerId: String, modeloId: String, sucesso: Boolean, latenciaMs: Long, erro: ErroObservado? = null)
}
