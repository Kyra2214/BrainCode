package com.sandbox.app

import android.content.Context
import com.brain.discovery.ExplorerCandidate
import com.brain.discovery.ExplorerIntelligencePipeline
import com.brain.discovery.ExplorerItemType
import com.brain.discovery.ExplorerRegion
import com.brain.discovery.ExplorerSource
import com.brain.discovery.ExplorerLicense
import com.brain.discovery.OpenSourceStatus
import com.brain.delivery.DeliveryReceipt
import com.brain.delivery.LocalDeliveryPackager
import com.brain.delivery.ObservableDelivery
import com.brain.events.BrainEvent
import com.brain.events.EventStore
import com.brain.events.FileEventStore
import com.brain.memory.Experiencia
import com.brain.memory.ExperienceMemory
import com.brain.memory.FileExperienceMemory
import com.brain.memory.ResultadoExperiencia
import com.brain.prompt.DefaultPromptGenerator
import com.brain.prompt.PromptGerado
import com.brain.prompt.PromptLibrary
import com.brain.core.Roadmap
import com.brain.core.Tarefa
import com.brain.skill.SkillManifest
import com.brain.skill.SkillRecord
import com.brain.skill.SkillRegistry
import com.brain.skill.TrustLevel
import com.brain.skill.RooftsSkillManifestBridge
import com.brain.workflow.WorkflowCatalog
import com.brain.workflow.WorkflowDocument
import java.io.File
import java.time.Instant

/**
 * Fachada Android para os subsistemas Brain locais e persistentes.
 *
 * Importante: esta fachada NÃO expõe mais um workflow próprio. Qualquer execução de
 * workflow/health-check passa exclusivamente por BrainSandboxController.healthCheck(),
 * que roda o ciclo autorizado real (BrainSandboxController -> BrainSandboxExecutionBridge
 * -> CicloExecucaoPlano). Isso elimina a duplicidade histórica entre um WorkflowEngine
 * "local/demonstrativo" aqui e o caminho real do Sandbox (ver PLANO_LIMPEZA_E_REESTRUTURACAO,
 * Fase 2).
 */
class BrainIntegrationFacade(private val context: Context, private val stateDir: File) {
    private val skills = SkillRegistry()
    private val memory: ExperienceMemory = FileExperienceMemory(File(stateDir, "memory.jsonl"))
    private val discovery = ExplorerIntelligencePipeline()
    private val delivery = ObservableDelivery()
    private val promptGenerator = DefaultPromptGenerator()
    private val events: EventStore = FileEventStore(File(stateDir, "events.jsonl"))
    private val workflowCatalog = WorkflowCatalog(File(stateDir, "workflows"))

    init {
        stateDir.mkdirs()
        skills.register(
            SkillManifest(
                id = "sandbox-health",
                name = "Sandbox Health",
                version = "1.0.0",
                description = "Verifica a saúde do runtime Sandbox.",
                category = "validation",
                capabilities = setOf("sandbox.health"),
                trustLevel = TrustLevel.CORE,
                sourceId = "brain-builtin",
                license = "Apache-2.0"
            )
        )
        runCatching {
            SkillsCatalogoLoader.load(context).forEach { entry ->
                runCatching { skills.register(entry.paraManifest()) }
            }
        }
        // Roofts é descoberto no registry como conteúdo metodológico, mas permanece desabilitado:
        // descoberta, habilitação e autorização são estados distintos.
        runCatching {
            RooftsSkillLoader.loadCatalog(context).metadata().forEach { skill ->
                runCatching { RooftsSkillManifestBridge.registerDiscovered(skill, skills) }
            }
        }
    }

    fun enabledSkills(): List<SkillRecord> = skills.listEnabled()

    fun availableWorkflows(): List<WorkflowDocument> = workflowCatalog.list()
    fun enabledWorkflows(): List<WorkflowDocument> = workflowCatalog.enabled()
    fun enableWorkflow(id: String): WorkflowDocument = workflowCatalog.enable(id)
    fun disableWorkflow(id: String) = workflowCatalog.disable(id)
    fun backupWorkflows(output: File) = workflowCatalog.backup(output)
    fun restoreWorkflows(input: File) = workflowCatalog.restore(input)

    suspend fun recordExperience(runId: String, success: Boolean) {
        memory.registrar(
            Experiencia(
                id = "ui:$runId",
                tarefaId = "ui:$runId",
                problema = "operação iniciada pela aba Operações",
                estrategiaUsada = "local-sandbox",
                promptUsado = null,
                resultado = if (success) ResultadoExperiencia.SUCESSO else ResultadoExperiencia.FALHA,
                custoEstimado = 0.0,
                tempoTotalMs = 0,
                erros = emptyList(),
                registradoEm = Instant.now()
            )
        )
        emit(runId, "memory", "ExperienceRecorded", mapOf("success" to success.toString()))
    }

    suspend fun memoryRate(): Double = memory.taxaSucessoPorEstrategia("local-sandbox")

    /** Geração determinística de prompts usando somente a biblioteca local fornecida. */
    suspend fun generatePrompts(roadmap: Roadmap, library: PromptLibrary): List<PromptGerado> =
        promptGenerator.gerarPromptsPorRoadmap(roadmap, library)

    /** Gera prompt determinístico de correção sem provider externo. */
    suspend fun generateCorrectionPrompt(tarefa: Tarefa, reason: String, library: PromptLibrary): PromptGerado =
        promptGenerator.gerarPromptDeCorrecao(tarefa, reason, library)

    /** Eventos locais persistentes; sobrevivem ao reinício da instância/app. */
    fun localEvents(runId: String? = null): List<BrainEvent> = events.replay(runId)
    fun localEventsHealthy(): Boolean = events.verifyIntegrity()

    private fun emit(runId: String, taskId: String, type: String, payload: Map<String, String>) {
        val nextSequence = events.replay().size.toLong()
        events.append(
            BrainEvent(
                runId = runId,
                sessionId = "android-local",
                taskId = taskId,
                type = type,
                sequence = nextSequence,
                payload = payload,
                idempotencyKey = "$runId:$taskId:$type:$nextSequence"
            )
        )
    }

    /** Publica somente um recibo local dos artefatos; não envia dados para rede. */
    fun publishLocalDelivery(root: File, runId: String): DeliveryReceipt = delivery.publish(
        root = root,
        runId = runId,
        sessionId = "android-local",
        taskId = "workspace-delivery",
        stepId = "collect-artifacts",
        eventId = "delivery:$runId",
        provider = "local-sandbox",
        startedAt = Instant.now()
    )

    /** Publishes the local receipt and creates a real downloadable archive. */
    fun packageLocalDelivery(root: File, runId: String): File {
        publishLocalDelivery(root, runId)
        val output = File(File(stateDir, "deliveries"), "$runId.zip")
        return LocalDeliveryPackager.packageRoot(root, output)
    }

    fun discoverBuiltInCandidate(): com.brain.discovery.ExplorerPipelineResult = discovery.run(
        weekEpochMs = System.currentTimeMillis(),
        sources = listOf(
            ExplorerSource("builtin", "Catálogo local", ExplorerRegion.GLOBAL, priority = 1, official = true),
            ExplorerSource("china-seed", "Explorer China Seed", ExplorerRegion.CHINA, priority = 1, official = true)
        ),
        candidates = runCatching { ExplorerSeedLoader.load(context).map { it.paraCandidate() } }.getOrElse { emptyList() } + listOf(
            ExplorerCandidate(
                id = "braincode",
                name = "BrainCode",
                type = ExplorerItemType.FRAMEWORK,
                region = ExplorerRegion.GLOBAL,
                officialUrl = "https://github.com/Kyra2214/BrainCode",
                repositoryUrl = "https://github.com/Kyra2214/BrainCode",
                description = "Runtime Brain e Sandbox Mobile.",
                capabilities = setOf("sandbox", "workflow", "policy"),
                openSource = OpenSourceStatus.OPEN,
                license = ExplorerLicense.APACHE_2,
                licenseVerified = true,
                sourcePriority = 1,
                confidence = 1.0
            )
        )
    )
}
