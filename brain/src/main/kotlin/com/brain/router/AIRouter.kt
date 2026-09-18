package com.brain.router

/**
 * Etapa 5 do plano de integração Brain+Sandbox
 * (docs/PLANO_INTEGRACAO_BRAIN_SANDBOX.md): o Router que escolhe
 * provider/modelo por conta do usuário — ele nunca vê essa escolha. Recebe
 * a capacidade/papel definido pelo Planner (Etapa 6) e decide sozinho.
 *
 * Equivalente ao LocalAIRouter + IARoutingProfile do IaBrain, agora
 * decidindo sobre ProviderModel + LiveStats em vez de números fixos.
 *
 * A decisão é dinâmica: dois pedidos idênticos podem escolher provedores
 * diferentes se o LiveStats mudou entre um e outro (quota caiu, erro
 * recente, latência subiu).
 */
data class RoutingProfile(
    val providerId: String,
    val modeloId: String,
    val qualityScore: Double,
    val isDefaultProfile: Boolean = true
)

data class RoutingDecision(
    val escolhido: ProviderModel,
    val alternativas: List<ProviderModel>,
    val motivo: String,
    val accountId: String? = null
)

/**
 * Continua puro do ponto de vista de "não faz a chamada de rede" — mas
 * consulta ApiCatalog.statsAtuais() para cada candidato antes de decidir.
 *
 * O Router entrega a ordem completa de candidatos. O BrainExecutionCoordinator
 * executa o escolhido e, se houver falha/limite, percorre automaticamente as
 * alternativas gratuitas e por fim pode cair na IA local do Sandbox.
 */
interface AIRouter {
    fun decidir(papel: PapelPipeline, catalog: ApiCatalog, profiles: List<RoutingProfile>): RoutingDecision?

    /** Mantido como helper para consumidores que queiram avançar manualmente na fila. */
    fun proximaAlternativa(decisaoAnterior: RoutingDecision): ProviderModel?
}
