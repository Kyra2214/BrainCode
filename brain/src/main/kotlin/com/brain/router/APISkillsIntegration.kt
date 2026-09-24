package com.brain.router

import com.brain.capability.CapabilityRegistry
import com.brain.capability.CostClass
import com.brain.events.BrainEvent
import com.brain.events.EventStore
import com.brain.memory.LayeredMemory
import com.brain.memory.Provenance
import com.brain.skill.SkillRegistry
import com.brain.skill.TrustLevel
import java.time.Instant

/**
 * Integração de APIs dinâmicas (Marco 5.3) + Skills (Marco 2.5).
 * - Resolução de API usa o ApiCatalog real (granularidade por PapelPipeline + ProviderModel,
 *   com LiveStats vivo via statsAtuais/registrarResultado) — não um "discoverAPIs(capability)"
 *   que não existe.
 * - Skills continuam sendo resolvidas pelo SkillRegistry real (findForCapability + TrustLevel),
 *   sem um conceito de "readiness" numérico que o registry não calcula.
 * - LayeredMemory é usado só pelas suas camadas reais (facts/episodes com Provenance), não por
 *   um "experience"/"knowledge" que não existe nela — quem de fato mede taxa de sucesso por
 *   estratégia é ExperienceMemory (fora do escopo deste router) e o próprio LiveStats do
 *   ApiCatalog, já consultado aqui.
 */

data class APIResolution(
    val providerId: String,
    val modeloId: String,
    val papel: PapelPipeline,
    val fallbackProvider: String?,
    val cost: CostClass,
    val latencyEstimateMs: Long?
)

data class SkillResolution(
    val skillId: String,
    val nome: String,
    val capability: String,
    val trustLevel: TrustLevel,
    val sourceType: String // "local" ou "roofts"
)

class APISkillsRouter(
    private val apiCatalog: ApiCatalog,
    private val skillRegistry: SkillRegistry,
    /** Reservado para checagem futura de que a capability resolvida está declarada no registry
     *  comum; nenhum método abaixo consome isto ainda (também não era usado no arquivo original). */
    private val capabilityRegistry: CapabilityRegistry,
    private val memory: LayeredMemory,
    private val eventStore: EventStore,
    private val sessionId: String = "api-skills-router"
) {

    /**
     * Resolve provider/modelo dinâmico com fallback para um papel do pipeline.
     * ApiCatalog não indexa por capability livre — só por PapelPipeline — então esse é o
     * parâmetro real de entrada, não a String "capability" do arquivo original.
     * Ranqueia candidatos pelo LiveStats real (taxaSucessoRecente, depois latência), em vez de
     * uma camada de "experiência" que não existe em LayeredMemory.
     */
    suspend fun resolveAPI(papel: PapelPipeline, runId: String): APIResolution {
        val candidatos = apiCatalog.listarPorPapel(papel)
        if (candidatos.isEmpty()) {
            error("Nenhuma API disponível para papel: $papel")
        }

        val ranqueados = candidatos.sortedWith(
            compareByDescending<ProviderModel> {
                apiCatalog.statsAtuais(it.providerId, it.modeloId)?.taxaSucessoRecente ?: 0.5
            }.thenBy {
                apiCatalog.statsAtuais(it.providerId, it.modeloId)?.latenciaMediaMs ?: Long.MAX_VALUE
            }
        )

        val escolhido = ranqueados.first()
        val fallback = ranqueados.getOrNull(1)
        val stats = apiCatalog.statsAtuais(escolhido.providerId, escolhido.modeloId)

        val resolution = APIResolution(
            providerId = escolhido.providerId,
            modeloId = escolhido.modeloId,
            papel = papel,
            fallbackProvider = fallback?.providerId,
            cost = escolhido.cost,
            latencyEstimateMs = stats?.latenciaMediaMs
        )

        appendEvent(
            runId = runId,
            taskId = papel.name,
            type = "api.resolution",
            payload = mapOf(
                "papel" to papel.name,
                "provider" to resolution.providerId,
                "modelo" to resolution.modeloId,
                "fallback" to (resolution.fallbackProvider ?: "none")
            )
        )

        return resolution
    }

    /**
     * Resolve skill como alternativa a provider externo.
     * SkillRegistry não tem um score de "readiness"; o sinal real disponível é TrustLevel
     * (CORE/VERIFIED/COMMUNITY/UNTRUSTED) mais enabled/revoked, então é isso que decide.
     */
    suspend fun resolveSkill(
        capability: String,
        runId: String,
        minTrust: TrustLevel = TrustLevel.COMMUNITY
    ): SkillResolution? {
        val skills = skillRegistry.findForCapability(capability)
        if (skills.isEmpty()) return null

        // TrustLevel é declarado CORE, VERIFIED, COMMUNITY, UNTRUSTED — ordinal menor é mais confiável.
        val elegiveis = skills.filter { it.manifest.trustLevel.ordinal <= minTrust.ordinal }

        if (elegiveis.isEmpty()) {
            appendEvent(
                runId = runId,
                taskId = capability,
                type = "skill.resolution_failed",
                payload = mapOf(
                    "capability" to capability,
                    "minTrust" to minTrust.name,
                    "availableSkills" to skills.size.toString()
                )
            )
            return null
        }

        val melhor = elegiveis.minByOrNull { it.manifest.trustLevel.ordinal } ?: elegiveis.first()

        val resolution = SkillResolution(
            skillId = melhor.manifest.id,
            nome = melhor.manifest.name,
            capability = capability,
            trustLevel = melhor.manifest.trustLevel,
            sourceType = if (melhor.manifest.sourceId.startsWith("roofts")) "roofts" else "local"
        )

        appendEvent(
            runId = runId,
            taskId = capability,
            type = "skill.activated",
            payload = mapOf(
                "skillId" to resolution.skillId,
                "capability" to capability,
                "trustLevel" to resolution.trustLevel.name
            )
        )

        return resolution
    }

    /**
     * Estratégia composta: preferir skill local se disponível,
     * senão fazer fallback para API com retry.
     */
    suspend fun resolveComposite(
        capability: String,
        papel: PapelPipeline,
        runId: String,
        retryCount: Int = 2
    ): Any {
        val skill = resolveSkill(capability, runId)
        if (skill != null && skill.sourceType == "local") {
            return skill
        }

        var lastError: Exception? = null
        repeat(retryCount) { attempt ->
            try {
                val apiResolution = resolveAPI(papel, runId)

                appendEvent(
                    runId = runId,
                    taskId = capability,
                    type = "composite.api_selected",
                    payload = mapOf(
                        "capability" to capability,
                        "attempt" to (attempt + 1).toString(),
                        "provider" to apiResolution.providerId
                    )
                )

                return apiResolution
            } catch (e: Exception) {
                lastError = e
                if (attempt < retryCount - 1) {
                    kotlinx.coroutines.delay(100L * (attempt + 1))
                }
            }
        }

        error("Falha ao resolver capability $capability após $retryCount tentativas: ${lastError?.message}")
    }

    /**
     * Registra sucesso de API para aprendizado: alimenta o LiveStats real do ApiCatalog
     * (é literalmente para isso que registrarResultado existe) e deixa um fato validado
     * na LayeredMemory para contexto qualitativo futuro.
     */
    suspend fun recordSuccess(
        papel: PapelPipeline,
        providerId: String,
        modeloId: String,
        latenciaMs: Long,
        runId: String
    ) {
        apiCatalog.registrarResultado(providerId, modeloId, sucesso = true, latenciaMs = latenciaMs)

        memory.rememberFact(
            value = "provider=$providerId modelo=$modeloId papel=${papel.name} resultado=sucesso",
            provenance = Provenance(source = "APISkillsRouter", confidence = 0.9),
            validated = true
        )

        appendEvent(
            runId = runId,
            taskId = papel.name,
            type = "resolution.success",
            payload = mapOf(
                "papel" to papel.name,
                "provider" to providerId,
                "modelo" to modeloId
            )
        )
    }

    /**
     * Registra falha para degradação e fallback: também alimenta o LiveStats do ApiCatalog,
     * e fica como episódio (não como fato validado) na LayeredMemory.
     */
    suspend fun recordFailure(
        papel: PapelPipeline,
        providerId: String,
        modeloId: String,
        erro: String,
        runId: String
    ) {
        apiCatalog.registrarResultado(
            providerId, modeloId, sucesso = false, latenciaMs = 0L,
            erro = ErroObservado(TipoErro.classify(erro), Instant.now())
        )

        memory.rememberEpisode(
            value = "provider=$providerId modelo=$modeloId papel=${papel.name} erro=$erro",
            provenance = Provenance(source = "APISkillsRouter", confidence = 0.0)
        )

        appendEvent(
            runId = runId,
            taskId = papel.name,
            type = "resolution.failure",
            payload = mapOf(
                "papel" to papel.name,
                "provider" to providerId,
                "modelo" to modeloId,
                "erro" to erro
            )
        )
    }

    /** Único ponto de escrita no EventStore real: mesma convenção usada em
     * EventStoreTraceSink / RuntimeDoctorImpl / CreationWorkflowExecutor. */
    private fun appendEvent(runId: String, taskId: String, type: String, payload: Map<String, String>) {
        val sequence = eventStore.replay().size.toLong()
        eventStore.append(
            BrainEvent(
                runId = runId,
                sessionId = sessionId,
                taskId = taskId,
                type = type,
                sequence = sequence,
                payload = payload
            )
        )
    }
}
