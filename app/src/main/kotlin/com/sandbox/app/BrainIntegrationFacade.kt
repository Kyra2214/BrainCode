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
import com.brain.workflow.WorkflowEngine
import com.brain.workflow.WorkflowLeaseStore
import com.brain.workflow.WorkflowAutomationPolicy
import com.brain.workflow.WorkflowIntegrationService
import com.brain.workflow.WorkflowRunResult
import com.brain.workflow.WorkflowScheduler
import com.brain.workflow.WorkflowRunPort
import com.brain.workflow.WorkflowMarketplaceRegistry
import com.brain.workflow.WorkflowPackageManifest
import java.io.File
import java.time.Instant
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/**
 * Fachada Android para os subsistemas Brain locais e persistentes.
 *
 * Esta fachada NÃO autoriza nem executa workflow por conta própria. Ela guarda catálogo,
 * scheduler e engine (estado persistente) e os entrega a UM WorkflowIntegrationService,
 * cuja única saída de autorização/execução é a WorkflowRunPort do BrainSandboxController
 * (PolicyBroker + ActionGateway reais). Não existe aqui parâmetro `authorize` nem
 * `executeBody` fornecido pelo caller. Health-check continua em BrainSandboxController.healthCheck().
 * Ver docs/LEGADO_E_DECISOES.md, "Autorização de workflows automáticos".
 */
class BrainIntegrationFacade(private val context: Context, private val stateDir: File) {
    private val trustedSigningKeys = loadTrustedSigningKeys()
    private val skills = SkillRegistry(trustedSigningKeys, File(stateDir, "revoked-skills.tsv"))
    private val memory: ExperienceMemory = FileExperienceMemory(File(stateDir, "memory.jsonl"))
    private val discovery = ExplorerIntelligencePipeline()
    private val delivery = ObservableDelivery()
    private val promptGenerator = DefaultPromptGenerator()
    private val events: EventStore = FileEventStore(File(stateDir, "events.jsonl"))
    private val workflowCatalog = WorkflowCatalog(File(stateDir, "workflows"))
    private val marketplace = WorkflowMarketplaceRegistry(trustedSigningKeys)
    private val workflowScheduler = WorkflowScheduler(File(stateDir, "workflows/scheduler.json"))
    private val workflowEngine = WorkflowEngine(
        File(stateDir, "workflows/runs.json"),
        WorkflowLeaseStore(File(stateDir, "workflows/lease.json"))
    )
    @Volatile private var workflowService: WorkflowIntegrationService? = null

    init {
        stateDir.mkdirs()
        seedBuiltInWorkflows()
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

    private fun seedBuiltInWorkflows() {
        val names = listOf("organizar-contexto", "revisar-seguranca")
        names.forEach { id ->
            val destination = File(stateDir, "workflows/available/community/$id/WORKFLOW.md")
            if (destination.isFile) return@forEach
            runCatching {
                destination.parentFile?.mkdirs()
                context.assets.open("workflows/community/$id/WORKFLOW.md").use { input ->
                    destination.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
    }

    fun enabledSkills(): List<SkillRecord> = skills.listEnabled()

    fun availableWorkflows(): List<WorkflowDocument> = workflowCatalog.list()
    fun enabledWorkflows(): List<WorkflowDocument> = workflowCatalog.enabled()
    /** Habilitar só muda estado: não autoriza nada. O scheduler recusa o schedule que a policy não permite. */
    fun enableWorkflow(id: String): WorkflowDocument = workflowCatalog.enable(id).also { workflowScheduler.register(it) }
    fun disableWorkflow(id: String) { workflowCatalog.disable(id); workflowScheduler.unregister(id) }
    /** Motivo pelo qual o schedule do workflow não foi (ou não seria) ativado; null se não há recusa. */
    fun scheduleRefusal(id: String): String? = workflowCatalog.resolve(id)?.let { WorkflowAutomationPolicy.scheduleRefusal(it) }
    fun backupWorkflows(output: File) = workflowCatalog.backup(output)
    fun restoreWorkflows(input: File) = workflowCatalog.restore(input)
    fun listMarketplaceManifests(): List<WorkflowPackageManifest> = marketplace.list()
    fun resolveMarketplaceManifest(id: String, version: String): WorkflowPackageManifest? = marketplace.resolve(id, version)
    fun pinMarketplaceManifest(manifest: WorkflowPackageManifest): WorkflowPackageManifest = marketplace.pin(manifest)
    fun scheduledWorkflows() = enabledWorkflows().mapNotNull { workflowScheduler.get(it.id) }

    /**
     * Conecta o runner de workflows à porta do controller e inicia o scheduler (somente com o app aberto).
     * [canStartRun] é single-flight com o sandbox, NÃO autorização. Chamar de novo troca a porta.
     */
    fun attachWorkflowRunner(port: WorkflowRunPort, canStartRun: () -> Boolean) {
        workflowService?.stopScheduler()
        workflowService = WorkflowIntegrationService(
            catalog = workflowCatalog,
            scheduler = workflowScheduler,
            engine = workflowEngine,
            eventStore = events,
            port = port,
            canStartRun = canStartRun,
            owner = "android-app-scheduler"
        ).also { it.startScheduler() }
    }

    fun stopWorkflowRunner() {
        workflowService?.stopScheduler()
        workflowService = null
    }

    /** Execução manual de um workflow habilitado; passa pela mesma porta (PolicyBroker/ActionGateway) do scheduler. */
    suspend fun runWorkflowNow(id: String, runId: String): WorkflowRunResult {
        val service = workflowService ?: error("runner de workflows não conectado")
        return service.runWorkflow(id, runId)
    }

    private fun loadTrustedSigningKeys(): Map<String, ByteArray> = runCatching {
        val json = JSONObject(context.assets.open("braincode/trusted-signing-keys.json").bufferedReader().use { it.readText() })
        val keys = json.optJSONArray("keys") ?: JSONArray()
        buildMap {
            for (index in 0 until keys.length()) {
                val item = keys.getJSONObject(index)
                if (item.optString("status") == "active" && item.optString("algorithm") == "Ed25519") {
                    put(item.getString("keyId"), Base64.getDecoder().decode(item.getString("publicKeyDerBase64")))
                }
            }
        }
    }.getOrDefault(emptyMap())

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
