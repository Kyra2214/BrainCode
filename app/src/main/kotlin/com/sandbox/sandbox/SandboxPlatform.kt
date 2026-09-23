package com.sandbox.sandbox

import com.brain.capability.CapabilityAvailability
import com.brain.capability.CapabilityCategory
import com.brain.capability.CapabilityDefinition
import com.brain.capability.CapabilityProvenance
import com.brain.capability.CapabilityRegistry
import com.brain.gateway.ActionGateway
import com.brain.gateway.InMemoryActionAuditLog
import com.brain.policy.PolicyBroker
import com.brain.execution.RiskClass
import com.sandbox.runtime.ManagedSandboxRuntime
import java.io.File

/** Fachada das fases locais; mantém o runtime como única porta de execução autorizada. */
class SandboxPlatform(
    val runtime: ManagedSandboxRuntime,
    workspaceRoot: File,
    componentStateFile: File,
    serviceStateDir: File,
    policy: SandboxSecurityPolicy = SandboxSecurityPolicy(),
    networkPolicy: NetworkPolicy = NetworkPolicy(),
    trustedRemotePluginSourceIds: Set<String> = emptySet()
) {
    private val gatewayCapabilities = CapabilityRegistry(
        listOf("sandbox.git", "sandbox.toolchain", "sandbox.test", "sandbox.diagnostics", "sandbox.plugin").map { id ->
            CapabilityDefinition(
                id = id,
                name = id,
                description = "capability Android Sandbox via ActionGateway",
                category = CapabilityCategory.SANDBOX,
                ownerId = "sandbox-platform",
                origin = "android-sandbox",
                risk = RiskClass.LOW,
                availability = CapabilityAvailability.AVAILABLE,
                provenance = listOf(CapabilityProvenance("android-sandbox", "sandbox-platform"))
            )
        }
    )
    private val policyBroker = PolicyBroker(
        allowedCapabilities = setOf("sandbox.git", "sandbox.toolchain", "sandbox.test", "sandbox.diagnostics", "sandbox.plugin"),
        actorCapabilities = mapOf("sandbox-platform" to setOf("sandbox.git", "sandbox.toolchain", "sandbox.test", "sandbox.diagnostics", "sandbox.plugin"))
    ).withCapabilityRegistry(gatewayCapabilities)
    private val gatewayExecutionLogs = GatewayBackedSandboxExecutor.logs()
    /**
     * Fase 12 (ver PLANO_CONEXAO_FASE_12.md, seção 1, passo 3): este ActionGateway fica
     * deliberadamente sem `trace` (ExecutionTrace/TraceSink). SandboxPlatform não recebe nem
     * constrói um EventStore — é uma fachada local das fases do sandbox (git, toolchain,
     * plugins, diagnostics), sem sessão/runId de conversação para correlacionar. Criar um
     * EventStore só para isso duplicaria o armazenamento em vez de reaproveitar o que já existe
     * em BrainSandboxController (que é quem tem o EventStore real da sessão). Se este caminho
     * precisar de trace no futuro, o EventStore deve ser injetado de fora (do mesmo ponto que
     * hoje monta BrainSandboxController), nunca criado aqui.
     */
    private val actionGateway = ActionGateway(
        registry = gatewayCapabilities,
        policy = policyBroker,
        executor = SandboxActionExecutor(ManagedRuntimeExecutor(runtime), gatewayExecutionLogs),
        audit = InMemoryActionAuditLog()
    )
    private val securedExecutor = SecureCommandExecutor(
        GatewayBackedSandboxExecutor(actionGateway, gatewayExecutionLogs), policy
    )
    private val remotePluginCatalog = RemotePluginCatalog(trustedRemotePluginSourceIds)
    /**
     * Único caminho autorizado a rodar `bash -c` — e apenas para os scripts literais do
     * catálogo interno (plugins, toolchains, checagens do self-check), validados por hash.
     * Usado só por [plugins], [toolchains] e pelo self-check (via [installerExecutor]);
     * qualquer outro comando (chat, terminal, agente) continua exclusivamente em
     * [securedExecutor], que recusa `bash`/`sh` por completo. Ver Fase 1 do plano de
     * limpeza: antes deste executor, todo instalador do catálogo (`bash -c ...`) caía no
     * mesmo bloqueio de shell livre do securedExecutor e falhava sempre.
     *
     * O provider inclui `remotePluginCatalog.components()` — plugins remotos só entram
     * ali depois de passar pelo portão de confiança do import (fonte na allowlist +
     * hash SHA-256 do artefato verificado em RemotePluginCatalog.importSnapshot). Uma
     * vez aceitos, seus scripts ficam confiáveis aqui sem recriar o executor.
     */
    val installerExecutor: SandboxCommandExecutor = TrustedInstallerExecutor(
        GatewayBackedSandboxExecutor(actionGateway, gatewayExecutionLogs),
        policy,
        trustedScriptsProvider = {
            TrustedInstallerCatalog.allTrustedScripts() + TrustedInstallerCatalog.scriptsFor(remotePluginCatalog.components())
        }
    )
    private val componentJsonFile = File(componentStateFile.parentFile, "components.json")
    private val pluginSnapshotStore = PluginSnapshotStore(
        File(componentJsonFile.parentFile ?: componentJsonFile.absoluteFile.parentFile, "plugin_snapshots.json"),
        File(componentJsonFile.parentFile ?: componentJsonFile.absoluteFile.parentFile, "plugin_history.jsonl")
    )
    val plugins = SearchablePluginManager(
        installerExecutor,
        JsonComponentRepository(componentJsonFile, legacyTsvFile = componentStateFile),
        catalogProvider = { (BuiltInCatalog.all + remotePluginCatalog.components()).distinctBy { it.id } },
        snapshotStore = pluginSnapshotStore
    )
    val workspace = WorkspaceManager(workspaceRoot)
    val services = ServiceManager(securedExecutor, serviceStateDir, NetworkPolicyBroker(networkPolicy))
    val git = GitManager(securedExecutor)
    val diagnostics = SandboxDiagnostics(securedExecutor)
    val testLab = TestLab(securedExecutor)
    val toolchains = ToolchainManager(installerExecutor, File(workspaceRoot, "toolchains"))
    val security = SecurityAssessmentEngine()
    val securityScanner = SecurityProjectScanner()
    val securityScenarios = SecurityScenarioCatalog.baseline
    val securityPolicy: SandboxSecurityPolicy = policy
    /** Corpus de regressão persistente, executado somente com resultados sintéticos determinísticos. */
    val securityCorpus = SecurityRegressionCorpus(File(workspaceRoot, "security/security_regression_corpus.jsonl"))

    /** Executa scanner + probes determinísticos + assessment e registra os resultados observados. */
    fun runSecurityRegression(scanRoot: File): SecurityAssessment {
        val scan = securityScanner.scan(scanRoot)
        val results = securityCorpus.runDeterministic(securityScenarios)
        val assessment = security.evaluate(scan, securityScenarios, results)
        securityCorpus.record(assessment.lab, results)
        return assessment
    }

    fun securityCorpusDigest(): String = securityCorpus.digest()

    /** Importa um snapshot já coletado; não realiza rede, instalação ou execução. */
    fun importRemotePluginSnapshot(snapshot: RemoteCatalogSnapshot): RemoteCatalogResult = remotePluginCatalog.importSnapshot(snapshot)

    fun close() = runtime.shutdown()
}
