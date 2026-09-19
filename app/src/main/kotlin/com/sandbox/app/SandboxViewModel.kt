package com.sandbox.app

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sandbox.android.AndroidSandboxFactory
import com.sandbox.agent.BrainSandboxController
import com.sandbox.agent.ResultadoCiclo
import com.sandbox.agent.ResultadoPasso
import com.sandbox.resource.SandboxResourceManager
import com.sandbox.runtime.ExecutionLog
import com.sandbox.runtime.ManagedSandboxRuntime
import com.sandbox.runtime.FileExecutionLogRepository
import com.sandbox.runtime.SandboxProcessLauncher
import com.sandbox.sandbox.BuiltInCatalog
import com.sandbox.sandbox.ComponentKind
import com.sandbox.sandbox.InstallationState
import com.sandbox.sandbox.InstalledComponent
import com.sandbox.sandbox.SandboxComponent
import com.sandbox.sandbox.SandboxPlatform
import com.sandbox.sandbox.SecurityAssessment
import com.sandbox.sandbox.PluginOperationRecord
import com.sandbox.sandbox.PluginSnapshot
import com.sandbox.sandbox.ToolchainStatus
import com.sandbox.sandbox.SelfCheckReport
import com.sandbox.sandbox.SelfCheckSection
import com.sandbox.sandbox.SelfCheckItem
import com.sandbox.sandbox.SelfCheckStatus
import com.brain.planner.PlanoExecucao
import com.brain.prompt.InMemoryPromptLibrary
import com.brain.prompt.PromptLibraryLoader
import com.brain.memory.FileKnowledgeMemory
import com.brain.memory.KnowledgeLearningCycle
import com.brain.events.FileEventStore
import com.sandbox.sandbox.Project
import com.sandbox.sandbox.ServiceStatus
import com.sandbox.sandbox.BuiltInServices
import java.io.File
import com.sandbox.runtime.NamespaceSupport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.brain.research.ResearchResult
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

sealed interface SandboxPhase {
    data object NotReady : SandboxPhase
    data class Downloading(val bytesDownloaded: Long, val totalBytes: Long) : SandboxPhase
    data class Preparing(val stage: String = "Preparando runtime", val bytesCompleted: Long = 0L, val totalBytes: Long = 0L) : SandboxPhase
    data object Ready : SandboxPhase
    data object Running : SandboxPhase
    data class Blocked(val reason: String) : SandboxPhase
}

data class QuickCommand(val label: String, val command: String)
data class TerminalEntry(
    val command: String,
    val output: String = "",
    val exitCode: Int? = null,
    val running: Boolean = true
)

enum class ChatRole { USER, ASSISTANT, ERROR, STEP }
data class ChatMessage(
    val role: ChatRole,
    val content: String,
    val promptActionId: String? = null,
    val contentType: GeneratedContentType = GeneratedContentType.TEXT,
    val researchSources: List<ResearchSourceUi> = emptyList(),
    val validationWarning: String? = null,
    val validationPassed: Boolean? = null
)

enum class SessionStatus { IDLE, RUNNING, AWAITING_APPROVAL, DONE, FAILED, BLOCKED }

enum class BrainUiStage {
    IDLE, PLANEJANDO, EXECUTANDO, VERIFICANDO, CRITICANDO, REVISE,
    CORRIGINDO, REEXECUTANDO, PASS, BLOCKED, FAILED, READY
}

data class ThreadSession(
    val id: String,
    val title: String,
    val workspaceProjectName: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val status: SessionStatus,
    val events: List<ThreadEvent>,
    val conversationContext: ConversationContext = ConversationContext()
)

data class SessionSummary(
    val id: String,
    val title: String,
    val workspaceProjectName: String?,
    val status: SessionStatus,
    val lastEventPreview: String,
    val updatedAt: Long
)

sealed interface ApiKeyTestUiState {
    data object Idle : ApiKeyTestUiState
    data object Testing : ApiKeyTestUiState
    data class Success(val message: String) : ApiKeyTestUiState
    data class Failure(val message: String) : ApiKeyTestUiState
}

data class CliToolCheck(val label: String, val script: String)
private val CLI_TOOL_CHECKS = listOf(
    CliToolCheck("git", "git --version"), CliToolCheck("curl", "curl --version | head -n 1"),
    CliToolCheck("sqlite3", "sqlite3 --version"), CliToolCheck("make", "make --version | head -n 1"),
    CliToolCheck("zip/unzip", "zip -v | head -n 1 && unzip -v | head -n 1"),
    CliToolCheck("pip (python3 -m pip)", "python3 -m pip --version"), CliToolCheck("npm", "npm -v")
)

val QUICK_COMMANDS = listOf(
    QuickCommand("git --version", "git --version"),
    QuickCommand("git clone", "git clone --depth 1 https://github.com/octocat/Hello-World.git /tmp/hello && ls /tmp/hello"),
    QuickCommand("pip --version", "python3 -m pip --version"),
    QuickCommand("pip install", "python3 -m pip install --quiet requests && python3 -c \"import requests; print(requests.__version__)\""),
    QuickCommand("npm -v", "npm -v"),
    QuickCommand("npm install", "cd /tmp && npm init -y >/dev/null 2>&1 && npm install --no-fund --no-audit lodash && node -e \"console.log(require('lodash').VERSION)\""),
    QuickCommand("node -v", "node -v"), QuickCommand("zip/unzip", "cd /tmp && echo conteudo > arquivo.txt && zip -q teste.zip arquivo.txt && unzip -l teste.zip"),
    QuickCommand("curl", "curl -sI https://example.com | head -n 1"), QuickCommand("sqlite3", "sqlite3 --version"),
    QuickCommand("gcc/make", "gcc --version | head -n 1 && make --version | head -n 1"), QuickCommand("apt list", "apt list --installed 2>/dev/null | wc -l")
)

/** Comandos com execução real (não vêm do catálogo /comandos). Sempre prioritários no dispatcher. */
val OPERATIONAL_SLASH_COMMANDS = listOf(
    "/run ", "/testlab", "/security", "/git status", "/git diff", "/git commit ", "/git push", "/workflow", "/approval demo",
    "/workspace new ", "/sqlite start", "/sqlite stop", "/discovery", "/deliver"
)

class SandboxViewModel(application: Application) : AndroidViewModel(application) {
    private val factory = AndroidSandboxFactory(application)
    val namespaceSupport = NamespaceSupport.detect()
    private var runtime: ManagedSandboxRuntime? = null

    override fun onCleared() {
        runCatching { runtime?.shutdown() }
        runtime = null
        brainController = null
        platform = null
        super.onCleared()
    }
    private var platform: SandboxPlatform? = null
    private var brainController: BrainSandboxController? = null
    private var brainIntegration: BrainIntegrationFacade? = null
    var workspaceProjectName by mutableStateOf("demo-project")
    private val sessionsFile = File(application.filesDir, "brain/thread-sessions.json")
    var sessions by mutableStateOf(loadSessions())
        private set
    var activeSessionId by mutableStateOf<String?>(null)
        private set
    val activeThreadEvents: List<ThreadEvent>
        get() = sessions.firstOrNull { it.id == activeSessionId }?.events.orEmpty()
    val sessionSummaries: List<SessionSummary>
        get() = sessions.sortedByDescending { it.updatedAt }.map { session ->
            val currentStatus = if (session.id == activeSessionId) statusForCurrentState() else session.status
            SessionSummary(session.id, session.title, session.workspaceProjectName, currentStatus, eventPreview(session.events.lastOrNull()), session.updatedAt)
        }

    var phase by mutableStateOf<SandboxPhase>(SandboxPhase.NotReady); private set
    val chatMessages = mutableStateListOf<ChatMessage>()
    private val conversationContextEngine = ConversationContextEngine()
    var chatInput by mutableStateOf("")
    var chatRunning by mutableStateOf(false); private set
    var brainUiStage by mutableStateOf(BrainUiStage.IDLE); private set
    private var chatJob: Job? = null

    private val apiKeyStore = ApiKeyStore(application)
    val apiProviders: List<ApiProvider> = runCatching { ApiKeyCatalogLoader.load(application) }.getOrElse { emptyList() }

    /** Catálogo /comandos (344 entradas PROMPT). Comandos operacionais reais têm prioridade — ver submitThreadInput. */
    val catalogoComandos: List<ComandoCatalogo> = runCatching { ComandosCatalogoLoader.load(application) }.getOrElse { emptyList() }
    private val catalogoPorComando: Map<String, ComandoCatalogo> = ComandosCatalogoLoader.indexar(catalogoComandos)

    /** Sugestões de autocomplete do composer: comandos operacionais primeiro, depois o catálogo. */
    val sugestoesDeComando: List<String> = (OPERATIONAL_SLASH_COMMANDS + catalogoComandos.map { it.comando }).distinct()

    /** UI -> Brain. O chat não chama runtime, llama.cpp, provider ou HTTP diretamente. */
    private val brainApiGateway = BrainApiGateway(
        apiProviders,
        apiKeyStore,
        learning = KnowledgeLearningCycle(FileKnowledgeMemory(File(application.filesDir, "brain/knowledge.jsonl")))
    )

    init {
        if (sessions.isEmpty()) createSession()
        else if (activeSessionId == null) {
            activeSessionId = sessions.maxByOrNull { it.updatedAt }?.id
            restoreChatFromActiveSession()
        }
        if (BuildConfig.E2E_FAKE_ROOTFS) prepareSandbox() else autoResumeSandboxIfAlreadyPrepared()
    }

    /**
     * O rootfs extraído persiste em disco entre aberturas do app, mas o estado `phase`
     * (em memória) sempre volta a NotReady quando o processo é recriado. Sem isto, o
     * usuário precisava tocar em "Preparar sandbox" de novo a cada abertura, mesmo com
     * o diagnóstico confirmando que o sandbox já estava pronto. Aqui checamos o disco e,
     * se já estiver pronto, refazemos a preparação (rápida, sem novo download/extração)
     * automaticamente, sem exigir toque do usuário.
     */
    private fun autoResumeSandboxIfAlreadyPrepared() {
        viewModelScope.launch {
            val ready = withContext(Dispatchers.IO) { factory.isRootfsReady() }
            if (ready) prepareSandbox()
        }
    }

    fun createSession() {
        val now = System.currentTimeMillis()
        val session = ThreadSession(UUID.randomUUID().toString(), "Nova tarefa", workspaceProjectName.takeIf { it.isNotBlank() }, now, now, SessionStatus.IDLE, emptyList())
        sessions = sessions + session
        activeSessionId = session.id
        chatMessages.clear()
        brainUiStage = BrainUiStage.IDLE
        chatRunning = false
        persistSessions()
    }

    fun switchSession(id: String): Boolean {
        if (phase == SandboxPhase.Running) return false
        val target = sessions.firstOrNull { it.id == id } ?: return false
        activeSessionId = target.id
        workspaceProjectName = target.workspaceProjectName.orEmpty()
        restoreChatFromActiveSession()
        return true
    }

    fun clearActiveSession() {
        val id = activeSessionId ?: return
        sessions = sessions.map { session -> if (session.id != id) session else session.copy(title = "Nova tarefa", events = emptyList(), conversationContext = ConversationContext(), updatedAt = System.currentTimeMillis()) }
        chatMessages.clear()
        brainUiStage = BrainUiStage.IDLE
        chatRunning = false
        diagnosticsReport = null
        lastBrainCycle = null
        selfCheckReport = null
        persistSessions()
    }

    fun appendThreadEvent(event: ThreadEvent) {
        val id = activeSessionId ?: return
        sessions = sessions.map { session ->
            if (session.id != id) session else session.copy(
                title = if (session.title == "Nova tarefa" && event is ThreadEvent.User) event.text.take(48) else session.title,
                updatedAt = System.currentTimeMillis(),
                status = statusForCurrentState(),
                events = session.events + event
            )
        }
        persistSessions()
    }

    private fun statusForCurrentState(): SessionStatus = when {
        pendingApprovalId != null -> SessionStatus.AWAITING_APPROVAL
        phase == SandboxPhase.Running -> SessionStatus.RUNNING
        phase is SandboxPhase.Blocked || brainUiStage == BrainUiStage.BLOCKED -> SessionStatus.BLOCKED
        chatMessages.lastOrNull()?.role == ChatRole.ERROR -> SessionStatus.FAILED
        chatMessages.isEmpty() && activeThreadEvents.isEmpty() -> SessionStatus.IDLE
        else -> SessionStatus.DONE
    }

    private fun restoreChatFromActiveSession() {
        chatMessages.clear()
        activeThreadEvents.forEach { event ->
            when (event) {
                is ThreadEvent.User -> chatMessages.add(ChatMessage(ChatRole.USER, event.text))
                is ThreadEvent.Agent -> chatMessages.add(ChatMessage(ChatRole.ASSISTANT, event.text, contentType = event.contentType, validationWarning = event.validationWarning, validationPassed = event.validationPassed))
                is ThreadEvent.System -> chatMessages.add(ChatMessage(ChatRole.ERROR, event.text))
                else -> Unit
            }
        }
    }

    private fun resolveConversation(prompt: String): ResolvedObjective {
        val resolved = conversationContextEngine.resolve(chatMessages.toList(), prompt)
        val id = activeSessionId
        if (id != null) {
            sessions = sessions.map { session ->
                if (session.id == id) session.copy(conversationContext = resolved.context, updatedAt = System.currentTimeMillis()) else session
            }
            persistSessions()
        }
        return resolved
    }

    private fun eventPreview(event: ThreadEvent?): String = when (event) {
        is ThreadEvent.User -> event.text
        is ThreadEvent.Agent -> event.text
        is ThreadEvent.System -> event.text
        is ThreadEvent.Report -> "${event.title}: ${event.body}"
        is ThreadEvent.Approval -> "Aprovação pendente"
        is ThreadEvent.Terminal -> "Terminal: exit ${event.result.exitCode}"
        is ThreadEvent.Diff -> "Diff: ${event.files.size} arquivo(s)"
        null -> "Sem eventos"
    }.take(60)

    fun quoteEvent(event: ThreadEvent) {
        chatInput = "Sobre este evento, corrija o problema:\n${eventPreview(event)}\n"
    }

    fun runGitDiff() {
        val p = platform ?: run { appendThreadEvent(ThreadEvent.System("Git diff indisponível: sandbox não está pronto.")); return }
        val project = workspaceProjects.firstOrNull { it.name == workspaceProjectName } ?: run {
            appendThreadEvent(ThreadEvent.System("Crie ou selecione um workspace antes de usar /git diff")); return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val log = p.git.diff("/home/sandbox/workspace/projects/${project.name}")
            val files = parseDiff(log.stdout)
            withContext(Dispatchers.Main) { appendThreadEvent(ThreadEvent.Diff(files)) }
        }
    }

    fun commitGit(message: String) {
        val p = platform ?: run { appendThreadEvent(ThreadEvent.System("Git commit indisponível: sandbox não está pronto.")); return }
        val project = workspaceProjects.firstOrNull { it.name == workspaceProjectName } ?: run {
            appendThreadEvent(ThreadEvent.System("Crie ou selecione um workspace antes de usar /git commit")); return
        }
        if (message.isBlank()) { appendThreadEvent(ThreadEvent.System("Use /git commit <mensagem>")); return }
        viewModelScope.launch(Dispatchers.IO) {
            val result = p.git.commit("/home/sandbox/workspace/projects/${project.name}", message.trim())
            withContext(Dispatchers.Main) { appendThreadEvent(ThreadEvent.Report("Git commit", result.stdout.ifBlank { result.stderr }.ifBlank { "commit concluído" })) }
        }
    }

    fun pushGit() {
        val p = platform ?: run { appendThreadEvent(ThreadEvent.System("Git push indisponível: sandbox não está pronto.")); return }
        val project = workspaceProjects.firstOrNull { it.name == workspaceProjectName } ?: run {
            appendThreadEvent(ThreadEvent.System("Crie ou selecione um workspace antes de usar /git push")); return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val result = p.git.push("/home/sandbox/workspace/projects/${project.name}")
            withContext(Dispatchers.Main) { appendThreadEvent(ThreadEvent.Report("Git push", result.stdout.ifBlank { result.stderr }.ifBlank { "push concluído" })) }
        }
    }

    private fun parseDiff(text: String): List<DiffFile> {
        val result = mutableListOf<DiffFile>()
        var path: String? = null
        var lines = mutableListOf<DiffLine>()
        fun flush() { path?.let { result += DiffFile(it, lines.toList()) }; lines = mutableListOf() }
        text.lineSequence().forEach { line ->
            if (line.startsWith("diff --git ")) {
                flush(); path = Regex(" b/(.+)$").find(line)?.groupValues?.get(1) ?: line
            } else if (path != null && (line.startsWith("+") || line.startsWith("-") || line.startsWith(" "))) {
                if (!line.startsWith("+++") && !line.startsWith("---")) lines += DiffLine(line.first(), line.drop(1))
            }
        }
        flush(); return result
    }

    private fun persistSessions() {
        sessionsFile.parentFile?.mkdirs()
        val array = JSONArray()
        sessions.forEach { session ->
            val events = JSONArray()
            session.events.forEach { event -> events.put(eventToJson(event)) }
            array.put(JSONObject().put("id", session.id).put("title", session.title).put("workspace", session.workspaceProjectName ?: JSONObject.NULL).put("createdAt", session.createdAt).put("updatedAt", session.updatedAt).put("status", session.status.name).put("events", events).put("conversationContext", contextToJson(session.conversationContext)))
        }
        sessionsFile.writeText(JSONObject().put("sessions", array).toString())
    }

    private fun eventToJson(event: ThreadEvent): JSONObject = when (event) {
        is ThreadEvent.User -> JSONObject().put("type", "user").put("text", event.text)
        is ThreadEvent.Agent -> JSONObject().put("type", "agent").put("text", event.text).put("validationWarning", event.validationWarning ?: JSONObject.NULL).put("validationPassed", event.validationPassed ?: JSONObject.NULL)
        is ThreadEvent.System -> JSONObject().put("type", "system").put("text", event.text)
        is ThreadEvent.Report -> JSONObject().put("type", "report").put("title", event.title).put("body", event.body)
        is ThreadEvent.Approval -> JSONObject().put("type", "approval").put("id", event.id)
        is ThreadEvent.Terminal -> JSONObject().put("type", "terminal").put("text", "exit ${event.result.exitCode}: ${event.result.stdout.take(500)}")
        is ThreadEvent.Diff -> JSONObject().put("type", "diff").put("files", JSONArray(event.files.map { file -> JSONObject().put("path", file.path).put("lines", JSONArray(file.lines.map { line -> "${line.prefix}${line.text}" })) }))
    }

    private fun loadSessions(): List<ThreadSession> = runCatching {
        val array = JSONObject(sessionsFile.readText()).optJSONArray("sessions") ?: JSONArray()
        (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            val eventsJson = item.optJSONArray("events") ?: JSONArray()
            ThreadSession(item.getString("id"), item.getString("title"), item.optString("workspace").takeIf { it.isNotBlank() && it != "null" }, item.getLong("createdAt"), item.getLong("updatedAt"), runCatching { SessionStatus.valueOf(item.getString("status")) }.getOrDefault(SessionStatus.IDLE), (0 until eventsJson.length()).mapNotNull { eventFromJson(eventsJson.getJSONObject(it)) }, contextFromJson(item.optJSONObject("conversationContext")))
        }
    }.getOrDefault(emptyList())

    private fun contextToJson(context: ConversationContext) = JSONObject()
        .put("idea", context.idea ?: JSONObject.NULL)
        .put("requirements", JSONArray(context.requirements))
        .put("decisions", JSONArray(context.decisions))
        .put("discarded", JSONArray(context.discarded))
        .put("pending", JSONArray(context.pending))
        .put("artifacts", JSONArray(context.artifacts))
        .put("references", JSONArray(context.references))

    private fun contextFromJson(json: JSONObject?): ConversationContext {
        if (json == null) return ConversationContext()
        fun list(name: String) = (0 until (json.optJSONArray(name)?.length() ?: 0)).map { json.optJSONArray(name)!!.getString(it) }
        return ConversationContext(json.optString("idea").takeIf { it.isNotBlank() && it != "null" }, list("requirements"), list("decisions"), list("discarded"), list("pending"), list("artifacts"), list("references"))
    }

    private fun eventFromJson(item: JSONObject): ThreadEvent? = when (item.optString("type")) {
        "user" -> ThreadEvent.User(item.optString("text"))
        "agent" -> ThreadEvent.Agent(item.optString("text"), validationWarning = item.optString("validationWarning").takeIf { it.isNotBlank() && it != "null" }, validationPassed = item.opt("validationPassed")?.takeUnless { it == JSONObject.NULL }?.let { item.optBoolean("validationPassed") })
        "system" -> ThreadEvent.System(item.optString("text"))
        "report" -> ThreadEvent.Report(item.optString("title"), item.optString("body"))
        "approval" -> ThreadEvent.Approval(item.optString("id"))
        "terminal" -> ThreadEvent.System(item.optString("text"))
        "diff" -> ThreadEvent.Diff((0 until item.optJSONArray("files").length()).map { index ->
            val file = item.optJSONArray("files").getJSONObject(index)
            DiffFile(file.optString("path"), (0 until file.optJSONArray("lines").length()).map { lineIndex ->
                val value = file.optJSONArray("lines").getString(lineIndex)
                DiffLine(value.firstOrNull() ?: ' ', value.drop(1))
            })
        })
        else -> null
    }

    private val apiKeyInputs = mutableStateMapOf<String, String>()
    val apiKeyTestState = mutableStateMapOf<String, ApiKeyTestUiState>()

    var installingComponentIds by mutableStateOf<Set<String>>(emptySet()); private set
    var pluginListVersion by mutableStateOf(0); private set
    private var statusCache by mutableStateOf<Map<String, InstalledComponent>>(emptyMap())
    var lastPluginError by mutableStateOf<String?>(null); private set
    var pluginSnapshots by mutableStateOf<List<PluginSnapshot>>(emptyList()); private set
    var pluginHistory by mutableStateOf<List<PluginOperationRecord>>(emptyList()); private set
    val sandboxReadyForPlugins get() = platform != null
    var commandInput by mutableStateOf("echo hello from sandbox")
    var lastResult by mutableStateOf<com.sandbox.runtime.SandboxExecutionResult?>(null); private set
    var lastExecution by mutableStateOf<ExecutionLog?>(null); private set
    var liveTerminalOutput by mutableStateOf(""); private set
    var terminalHistory by mutableStateOf<List<TerminalEntry>>(emptyList()); private set
    var commandHistory by mutableStateOf<List<String>>(emptyList()); private set
    private var commandHistoryCursor = 0
    fun clearTerminal() { commandInput = ""; lastResult = null; lastExecution = null; liveTerminalOutput = ""; terminalHistory = emptyList(); commandHistory = emptyList(); commandHistoryCursor = 0 }
    private fun appendTerminalOutput(chunk: String) {
        terminalHistory.lastOrNull()?.let { entry ->
            // O scrollback é a evidência da execução. A UI controla a janela
            // visível, mas nunca deve descartar o começo de uma saída longa.
            terminalHistory = terminalHistory.dropLast(1) + entry.copy(output = entry.output + chunk)
        }
    }
    fun recallPreviousCommand() {
        if (commandHistory.isEmpty()) return
        val index = (commandHistory.lastIndex - commandHistoryCursor).coerceAtLeast(0)
        commandInput = commandHistory[index]
        commandHistoryCursor = (commandHistoryCursor + 1).coerceAtMost(commandHistory.lastIndex)
    }
    var diagnosticsReport by mutableStateOf<String?>(null); private set
    var diagnosticsRunning by mutableStateOf(false); private set
    var lastBrainCycle by mutableStateOf<ResultadoCiclo?>(null); private set
    var lastTestLabReport by mutableStateOf<com.sandbox.sandbox.TestLabReport?>(null); private set
    var lastSecurityAssessment by mutableStateOf<SecurityAssessment?>(null); private set
    var toolchainStatuses by mutableStateOf<Map<String, ToolchainStatus>>(emptyMap()); private set
    var pendingApprovalId by mutableStateOf<String?>(null); private set
    private var pendingApprovalPlan: PlanoExecucao? = null
    private var pendingApprovalRunId: String? = null
    var workspaceProjects by mutableStateOf<List<Project>>(emptyList()); private set
    var lastGitStatus by mutableStateOf<String?>(null); private set
    var sqliteServiceStatus by mutableStateOf<ServiceStatus?>(null); private set
    var workspaceError by mutableStateOf<String?>(null); private set
    var brainSkillSummary by mutableStateOf<List<String>>(emptyList()); private set
    var lastWorkflowStatus by mutableStateOf<String?>(null); private set
    var memorySuccessRate by mutableStateOf<Double?>(null); private set
    var discoverySummary by mutableStateOf<String?>(null); private set
    var deliverySummary by mutableStateOf<String?>(null); private set
    var selfCheckReport by mutableStateOf<SelfCheckReport?>(null); private set
    var selfCheckRunning by mutableStateOf(false); private set
    var selfCheckStage by mutableStateOf<String?>(null); private set

    fun runFullSelfCheck() {
        val plat = platform ?: run { appendThreadEvent(ThreadEvent.System("Teste geral indisponível: sandbox não está pronto.")); return }
        if (selfCheckRunning) return
        if (phase != SandboxPhase.Ready) { appendThreadEvent(ThreadEvent.System("Teste geral indisponível: sandbox ocupado.")); return }
        viewModelScope.launch {
            selfCheckRunning = true; phase = SandboxPhase.Running
            try {
                selfCheckStage = "Verificando toolchains (Java, Python, Node, C/C++, Rust, Go, Android SDK)..."
                val toolchainItems = withContext(Dispatchers.IO) {
                    com.sandbox.sandbox.BuiltInToolchains.all.map { profile ->
                        val status = runCatching { plat.toolchains.refreshStatus(profile.id) }.getOrElse { e -> ToolchainStatus(profile.id, com.sandbox.sandbox.ToolchainState.FAILED, error = e.message ?: e.javaClass.simpleName) }
                        withContext(Dispatchers.Main) { toolchainStatuses = toolchainStatuses + (profile.id to status) }
                        val optIn = profile.id == "android"
                        val itemStatus = when (status.state) {
                            com.sandbox.sandbox.ToolchainState.INSTALLED -> SelfCheckStatus.OK
                            com.sandbox.sandbox.ToolchainState.NOT_INSTALLED -> if (optIn) SelfCheckStatus.WARNING else SelfCheckStatus.FAILED
                            else -> SelfCheckStatus.FAILED
                        }
                        val detail = when {
                            status.state == com.sandbox.sandbox.ToolchainState.INSTALLED -> status.versionOutput.lineSequence().firstOrNull()?.take(120) ?: "instalado"
                            optIn && status.state == com.sandbox.sandbox.ToolchainState.NOT_INSTALLED -> "opcional — instale na aba Operações se precisar"
                            else -> (status.error ?: "não encontrado").take(200)
                        }
                        SelfCheckItem(profile.displayName, itemStatus, detail)
                    }
                }
                selfCheckStage = "Verificando ferramentas de linha de comando..."
                val cliItems = withContext(Dispatchers.IO) {
                    CLI_TOOL_CHECKS.map { check ->
                        val active = runtime
                        if (active == null) SelfCheckItem(check.label, SelfCheckStatus.FAILED, "runtime indisponível") else {
                            val e = runCatching { active.execute(listOf("bash", "-c", check.script), timeoutSeconds = 15, workingDir = "/home/sandbox") }.getOrNull()
                            when {
                                e == null -> SelfCheckItem(check.label, SelfCheckStatus.FAILED, "falha ao executar a checagem")
                                e.succeeded -> SelfCheckItem(check.label, SelfCheckStatus.OK, e.stdout.lineSequence().firstOrNull { it.isNotBlank() }?.take(120) ?: "instalado")
                                else -> SelfCheckItem(check.label, SelfCheckStatus.FAILED, e.stderr.ifBlank { e.stdout }.ifBlank { "comando não encontrado" }.take(160))
                            }
                        }
                    }
                }
                selfCheckStage = "Verificando plugins e ferramentas opcionais instalados..."
                val pluginItems = withContext(Dispatchers.IO) {
                    plat.plugins.components().mapNotNull { c ->
                        val cached = plat.plugins.status(c.id)
                        if (cached == null || cached.state != InstallationState.INSTALLED) return@mapNotNull null
                        val works = runCatching { plat.plugins.validate(c) }.getOrDefault(false)
                        SelfCheckItem(c.name, if (works) SelfCheckStatus.OK else SelfCheckStatus.FAILED, if (works) (cached.version ?: "instalado") else "validação falhou")
                    }
                }
                selfCheckStage = "Conferindo arquivos do rootfs extraído no disco..."
                val rootfsText = withContext(Dispatchers.IO) { runCatching { factory.inspectExtractedRootfs() }.getOrElse { "Falha ao inspecionar rootfs: ${it.message}" } }
                val missing = rootfsText.contains("AUSENTE")
                val m = Regex("Total: (\\d+) arquivos, (\\d+) pastas, (\\d+) symlinks, (\\d+) MB").find(rootfsText)
                val detail = m?.let { "${it.groupValues[1]} arquivos, ${it.groupValues[2]} pastas, ${it.groupValues[4]} MB no disco" } ?: "tamanho não determinado"
                val report = SelfCheckReport(
                    System.currentTimeMillis(),
                    listOf(
                        SelfCheckSection("Toolchains", toolchainItems),
                        SelfCheckSection("Ferramentas de linha de comando", cliItems),
                        SelfCheckSection("Plugins opcionais instalados (catálogo)", pluginItems),
                        SelfCheckSection("Rootfs no disco", listOf(SelfCheckItem("Rootfs extraído (caminhos essenciais)", if (missing) SelfCheckStatus.FAILED else SelfCheckStatus.OK, detail)))
                    )
                )
                selfCheckReport = report
                val summary = report.sections.joinToString("\n\n") { s ->
                    "${s.title}: ${s.items.count { it.status == SelfCheckStatus.OK }}/${s.items.size} OK\n" +
                        s.items.joinToString("\n") { "  ${it.status} ${it.name} — ${it.detail}" }
                }
                appendThreadEvent(ThreadEvent.Report("Teste geral", summary))
            } finally {
                selfCheckStage = null; selfCheckRunning = false; phase = if (runtime != null) SandboxPhase.Ready else SandboxPhase.NotReady
            }
        }
    }

    fun runDiagnostics() {
        if (diagnosticsRunning) return
        viewModelScope.launch {
            diagnosticsRunning = true
            val report = withContext(Dispatchers.IO) { runCatching { factory.inspectExtractedRootfs() }.getOrElse { "Falha ao inspecionar rootfs: ${it.message}" } }
            diagnosticsReport = report
            appendThreadEvent(ThreadEvent.Report("Diagnóstico", report))
            diagnosticsRunning = false
        }
    }

    fun prepareSandbox() {
        if (phase is SandboxPhase.Downloading || phase is SandboxPhase.Preparing) return
        viewModelScope.launch {
            val ready = BuildConfig.E2E_FAKE_ROOTFS || withContext(Dispatchers.IO) { factory.isRootfsReady() }
            if (!ready) {
                phase = SandboxPhase.Downloading(0, 0)
                val manifests = try { ManifestLoader.loadAll(getApplication()) } catch (e: IllegalStateException) { phase = SandboxPhase.Blocked(e.message ?: "Manifesto inválido"); return@launch }
                val total = manifests.sumOf { it.sizeBytes }; var completed = 0L
                val results = withContext(Dispatchers.IO) {
                    manifests.mapIndexed { i, manifest ->
                        val r = factory.layerResourceManager(i).ensureAvailable(manifest) { d, _ -> phase = SandboxPhase.Downloading(completed + d, total) }
                        if (r is SandboxResourceManager.DownloadResult.Success) completed += manifest.sizeBytes
                        r
                    }
                }
                results.filterIsInstance<SandboxResourceManager.DownloadResult.Failure>().firstOrNull()?.let { phase = SandboxPhase.Blocked(it.reason); return@launch }
            }
            val roofts06Installed = BuildConfig.E2E_FAKE_ROOTFS || withContext(Dispatchers.IO) {
                factory.isRoofts06Installed()
            }
            phase = SandboxPhase.Preparing(
                if (roofts06Installed) "Inicializando RootFS" else "Atualizando Roofts 0.6 sobre o RootFS existente",
                0,
                1
            )
            try {
                runtime?.shutdown()
                val dir = File(getApplication<Application>().filesDir, "sandbox")
                val prepared = withContext(Dispatchers.IO) {
                    if (BuildConfig.E2E_FAKE_ROOTFS) {
                        ManagedSandboxRuntime(
                            launcher = E2eProcessLauncher(File(dir, "e2e-workspace")),
                            repository = FileExecutionLogRepository(File(dir, "e2e-runtime-logs")),
                            sessionId = factory.persistentSessionId()
                        )
                    } else {
                        factory.prepareManagedRuntime(factory.persistentSessionId()) { c, t, s -> phase = SandboxPhase.Preparing(s, c, t) }
                    }
                }
                runtime = prepared
                val promptLibrary = InMemoryPromptLibrary(
                    PromptLibraryLoader.fromJson(getApplication<Application>().assets.open("prompts_biblioteca.json").bufferedReader().use { it.readText() })
                )
                val preparedPlatform = SandboxPlatform(prepared, File(dir, "workspace"), File(dir, "components.tsv"), File(dir, "services"))
                platform = preparedPlatform
                val codeGenerationExecutor = CodeGenerationExecutor(
                    gateway = brainApiGateway,
                    workspace = preparedPlatform.workspace,
                    activeProjectName = { workspaceProjectName }
                )
                val promptGenerationExecutor = PromptGenerationExecutor(
                    promptLibrary = promptLibrary,
                    improver = GatewayPromptImprover(brainApiGateway)
                )
                val webResearchExecutor = WebResearchExecutor(
                    provider = com.brain.research.CompositeWebResearchProvider(
                        listOf(DuckDuckGoWebResearchProvider(), WikipediaWebResearchProvider())
                    )
                )
                brainController = BrainSandboxController(
                    prepared,
                    File(dir, "rootfs"),
                    apiKeyAvailable = { hasApiKeyInCatalog() },
                    promptLibrary = promptLibrary,
                    capabilityProviders = listOf(
                        PluginCatalogCapabilityProvider(statusOf = { id -> statusCache[id]?.state })
                    ),
                    capabilityExecutors = mapOf(
                        "workspace.generate" to codeGenerationExecutor,
                        "prompt.library.generate" to promptGenerationExecutor,
                        "prompt.library.write" to promptGenerationExecutor,
                        "sandbox.info" to webResearchExecutor,
                        "network.research" to webResearchExecutor
                    ),
                    events = FileEventStore(File(dir, "brain/chat-events.jsonl"))
                )
                brainIntegration = BrainIntegrationFacade(getApplication(), File(dir, "brain"))
                pluginListVersion++; refreshStatusCache(); refreshPluginAudit(); phase = SandboxPhase.Ready; refreshToolchains()
            } catch (e: Exception) { runtime = null; phase = SandboxPhase.Blocked(e.message ?: "Falha ao preparar o runtime") }
        }
    }

    fun installRoofts06OverExistingRootfs() {
        if (phase != SandboxPhase.Ready) return
        viewModelScope.launch {
            phase = SandboxPhase.Preparing("Instalando Roofts 0.6 sobre os três RootFS existentes", 0, 1)
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    runtime?.shutdown()
                    runtime = null
                    platform = null
                    factory.installRoofts06OverExistingRootfs()
                }
            }
            result.exceptionOrNull()?.let {
                phase = SandboxPhase.Blocked(it.message ?: "Falha ao instalar Roofts 0.6")
                return@launch
            }
            phase = SandboxPhase.NotReady
            prepareSandbox()
        }
    }

    fun runCommand() {
        val active = runtime ?: run { appendThreadEvent(ThreadEvent.System("Comando indisponível: sandbox não está pronto.")); return }
        if (phase != SandboxPhase.Ready) { appendThreadEvent(ThreadEvent.System("Comando indisponível: sandbox ocupado.")); return }
        val command = commandInput.trim()
        if (command.isEmpty()) return
        commandInput = ""
        commandHistory = (commandHistory + command).takeLast(100)
        commandHistoryCursor = 0
        terminalHistory = terminalHistory + TerminalEntry(command = command)
        viewModelScope.launch {
            phase = SandboxPhase.Running; liveTerminalOutput = ""
            val e = withContext(Dispatchers.IO) {
                runCatching {
                    active.execute(listOf("/bin/bash", "-c", command), 60, "/home/sandbox", onOutput = { line, stderr ->
                        viewModelScope.launch {
                            val chunk = if (stderr) "[stderr] $line\n" else "$line\n"
                            liveTerminalOutput += chunk
                            appendTerminalOutput(chunk)
                        }
                    })
                }.getOrNull()
            }
            if (e != null) {
                lastExecution = e; lastResult = e.toUiResult()
                val finalOutput = buildString {
                    if (e.stdout.isNotBlank()) append(e.stdout)
                    if (e.stderr.isNotBlank()) { if (isNotEmpty()) append('\n'); append("[stderr] "); append(e.stderr) }
                }
                terminalHistory.lastOrNull()?.let { entry -> terminalHistory = terminalHistory.dropLast(1) + entry.copy(output = finalOutput, exitCode = e.exitCode, running = false) }
            } else {
                terminalHistory.lastOrNull()?.let { entry -> terminalHistory = terminalHistory.dropLast(1) + entry.copy(output = "Comando falhou ao executar.\n", running = false) }
                appendThreadEvent(ThreadEvent.System("Comando falhou ao executar."))
            }
            phase = SandboxPhase.Ready
        }
    }

    private fun hasApiKeyInCatalog(): Boolean =
        apiProviders.any { provider -> apiKeyStore.get(provider.id)?.trim()?.isNotEmpty() == true }

    private fun postExecutionWarning(cycle: ResultadoCiclo): String? {
        val gate = cycle.posExecucao ?: return null
        if (cycle.aprovado) return null
        val details = gate.issues.ifEmpty {
            listOf("verification=${gate.verification.status}", "critique=${gate.critique.status}", "readiness=${gate.readiness.status}")
        }
        return "Atenção: a resposta não passou na verificação pós-execução. ${details.joinToString("; ")}"
    }

    /** Regra estrutural: a conversa Android entra no Brain; nenhum executor local é chamado pelo chat. */
    fun sendChatMessage() {
        val prompt = chatInput.trim(); if (prompt.isEmpty() || chatRunning) return
        chatMessages.add(ChatMessage(ChatRole.USER, prompt)); chatInput = ""; chatRunning = true; brainUiStage = BrainUiStage.PLANEJANDO
        appendThreadEvent(ThreadEvent.User(prompt))
        chatJob = viewModelScope.launch {
            val response = withContext(Dispatchers.IO) {
                runCatching {
                    val controller = brainController
                    if (controller != null && phase == SandboxPhase.Ready) {
                        val resolved = resolveConversation(prompt)
                        brainUiStage = BrainUiStage.EXECUTANDO
                        val cycle = controller.executeObjective(resolved.toBrainObjective(), "chat-${System.currentTimeMillis()}") { passo ->
                            viewModelScope.launch(Dispatchers.Main.immediate) { publishStep(passo) }
                        }
                        withContext(Dispatchers.Main.immediate) { publishCycleStages(cycle) }
                        val promptActionId = cycle.passos.firstOrNull { it.capacidade == "prompt.library.write" }?.actionId
                        val content = cycle.resposta ?: "Plano concluído: ${cycle.aprovado}"
                        val capability = cycle.passos.lastOrNull { it.resultado != null }?.capacidade
                        val evidence = cycle.passos.flatMap { it.executionEvidence }
                        ChatMessage(ChatRole.ASSISTANT, content, promptActionId = promptActionId, contentType = detectGeneratedContentType(content, capability, evidence), researchSources = cycle.researchSources.map { it.toUiSource() }, validationWarning = postExecutionWarning(cycle), validationPassed = cycle.aprovado)
                    } else {
                        ChatMessage(ChatRole.ERROR, "Brain indisponível enquanto o sandbox não está pronto. Prepare o sandbox e envie novamente.")
                    }
                }
                    .getOrElse { ChatMessage(ChatRole.ERROR, "Brain não conseguiu responder: ${it.message ?: it.javaClass.simpleName}") }
            }
            chatMessages.add(response); chatRunning = false
            if (response.role == ChatRole.ERROR) {
                brainUiStage = BrainUiStage.FAILED
            } else if (response.role == ChatRole.ASSISTANT) {
                // O relatório pós-execução é a fonte de verdade: uma resposta
                // produzida não significa que passou na verificação do Brain.
                // Antes, qualquer resposta que não fosse erro técnico virava
                // READY e escondia FAILED/REVISE no cabeçalho.
                val cyclePassed = response.validationPassed == true
                brainUiStage = if (cyclePassed) BrainUiStage.READY else BrainUiStage.FAILED
            }
            appendThreadEvent(if (response.role == ChatRole.ASSISTANT) ThreadEvent.Agent(response.content, response.promptActionId, response.contentType, response.researchSources, response.validationWarning, response.validationPassed) else ThreadEvent.System(response.content))
        }
    }
    /** Entrada única do composer Codex-style: texto livre ou comando operacional. */
    fun submitThreadInput() {
        val command = chatInput.trim()
        val lower = command.lowercase()
        when {
            lower == "/testlab" -> { chatMessages.add(ChatMessage(ChatRole.USER, command)); appendThreadEvent(ThreadEvent.User(command)); chatInput = ""; runTestLab() }
            lower == "/security" -> { chatMessages.add(ChatMessage(ChatRole.USER, command)); appendThreadEvent(ThreadEvent.User(command)); chatInput = ""; runSecurityAssessment() }
            lower == "/git status" -> { chatMessages.add(ChatMessage(ChatRole.USER, command)); appendThreadEvent(ThreadEvent.User(command)); chatInput = ""; inspectGitStatus() }
            lower == "/git diff" -> { chatMessages.add(ChatMessage(ChatRole.USER, command)); appendThreadEvent(ThreadEvent.User(command)); chatInput = ""; runGitDiff() }
            lower.startsWith("/git commit ") -> { chatMessages.add(ChatMessage(ChatRole.USER, command)); appendThreadEvent(ThreadEvent.User(command)); chatInput = ""; commitGit(command.substringAfter(" ").substringAfter(" ").trim()) }
            lower == "/git push" -> { chatMessages.add(ChatMessage(ChatRole.USER, command)); appendThreadEvent(ThreadEvent.User(command)); chatInput = ""; pushGit() }
            lower == "/workflow" -> { chatMessages.add(ChatMessage(ChatRole.USER, command)); appendThreadEvent(ThreadEvent.User(command)); chatInput = ""; runBrainWorkflow() }
            lower == "/approval demo" -> { chatMessages.add(ChatMessage(ChatRole.USER, command)); appendThreadEvent(ThreadEvent.User(command)); chatInput = ""; requestApprovalDemo() }
            lower.startsWith("/workspace new ") -> { val name = command.substringAfter(" ").substringAfter(" ").trim(); if (name.isNotBlank()) { chatMessages.add(ChatMessage(ChatRole.USER, command)); appendThreadEvent(ThreadEvent.User(command)); chatInput = ""; workspaceProjectName = name; createWorkspaceProject() } }
            lower == "/sqlite start" -> { chatMessages.add(ChatMessage(ChatRole.USER, command)); appendThreadEvent(ThreadEvent.User(command)); chatInput = ""; startSqliteService() }
            lower == "/sqlite stop" -> { chatMessages.add(ChatMessage(ChatRole.USER, command)); appendThreadEvent(ThreadEvent.User(command)); chatInput = ""; stopSqliteService() }
            lower == "/discovery" -> { chatMessages.add(ChatMessage(ChatRole.USER, command)); appendThreadEvent(ThreadEvent.User(command)); chatInput = ""; runDiscovery() }
            lower.startsWith("/run ") -> { val script = command.substringAfter(" ").trim(); if (script.isNotBlank()) { chatMessages.add(ChatMessage(ChatRole.USER, command)); appendThreadEvent(ThreadEvent.User(command)); chatInput = ""; commandInput = script; runCommand() } }
            lower == "/deliver" -> { chatMessages.add(ChatMessage(ChatRole.USER, command)); appendThreadEvent(ThreadEvent.User(command)); chatInput = ""; publishLocalDelivery() }
            command.startsWith("/") && command.length > 1 -> {
                val espaco = command.indexOf(' ')
                val base = (if (espaco == -1) command else command.take(espaco)).lowercase()
                val instrucao = if (espaco == -1) "" else command.substring(espaco + 1).trim()
                val doCatalogo = catalogoPorComando[base]
                chatMessages.add(ChatMessage(ChatRole.USER, command)); appendThreadEvent(ThreadEvent.User(command)); chatInput = ""
                if (doCatalogo != null) executarComandoDoCatalogo(doCatalogo, instrucao)
                else appendThreadEvent(ThreadEvent.System("Comando \"$base\" não reconhecido — nenhum comando operacional ou do catálogo corresponde a ele."))
            }
            else -> sendChatMessage()
        }
    }

    /** Roda uma entrada PROMPT do catálogo /comandos pelo mesmo caminho do chat livre (Brain), com prompt especializado. */
    private fun executarComandoDoCatalogo(entrada: ComandoCatalogo, instrucaoLivre: String) {
        val prompt = buildString {
            append(entrada.explicacao.ifBlank { entrada.descricaoCurta })
            append("\n\nObjetivo: "); append(entrada.objetivo)
            if (instrucaoLivre.isNotBlank()) { append("\n\nInstrução do usuário: "); append(instrucaoLivre) }
        }
        chatMessages.add(ChatMessage(ChatRole.USER, prompt))
        val resolved = resolveConversation(prompt)
        chatRunning = true
        viewModelScope.launch {
            val response = withContext(Dispatchers.IO) {
                runCatching {
                    val controller = brainController
                    if (controller != null && phase == SandboxPhase.Ready) {
                        val cycle = controller.executeObjective(resolved.toBrainObjective(), "cmd-${entrada.slug}-${System.currentTimeMillis()}") { passo ->
                            viewModelScope.launch(Dispatchers.Main.immediate) { publishStep(passo) }
                        }
                        val promptActionId = cycle.passos.firstOrNull { it.capacidade == "prompt.library.write" }?.actionId
                        val content = cycle.resposta ?: "Plano concluído: ${cycle.aprovado}"
                        val capability = cycle.passos.lastOrNull { it.resultado != null }?.capacidade
                        val evidence = cycle.passos.flatMap { it.executionEvidence }
                        ChatMessage(ChatRole.ASSISTANT, content, promptActionId = promptActionId, contentType = detectGeneratedContentType(content, capability, evidence), researchSources = cycle.researchSources.map { it.toUiSource() }, validationWarning = postExecutionWarning(cycle), validationPassed = cycle.aprovado)
                    } else {
                        ChatMessage(ChatRole.ERROR, "Brain indisponível enquanto o sandbox não está pronto. Prepare o sandbox e envie novamente.")
                    }
                }
                    .getOrElse { ChatMessage(ChatRole.ERROR, "Brain não conseguiu responder ao comando ${entrada.comando}: ${it.message ?: it.javaClass.simpleName}") }
            }
            chatMessages.add(response); chatRunning = false
            appendThreadEvent(if (response.role == ChatRole.ASSISTANT) ThreadEvent.Agent(response.content, response.promptActionId, response.contentType, response.researchSources, response.validationWarning, response.validationPassed) else ThreadEvent.System(response.content))
        }
    }
    private fun com.brain.research.ResearchResult.toUiSource() = ResearchSourceUi(title, source, url, relevantContent.take(240))

    private fun publishStep(passo: ResultadoPasso) {
        brainUiStage = when (passo.status) {
            com.sandbox.agent.StatusPasso.AGUARDANDO_APROVACAO, com.sandbox.agent.StatusPasso.NEGADO_PELA_POLICY, com.sandbox.agent.StatusPasso.BLOQUEADO_POR_DEPENDENCIA -> BrainUiStage.BLOCKED
            com.sandbox.agent.StatusPasso.REPROVADO -> BrainUiStage.FAILED
            com.sandbox.agent.StatusPasso.APROVADO -> BrainUiStage.EXECUTANDO
        }
        val message = "${passo.passoId}: ${passo.status.name}${passo.motivo?.let { " — $it" } ?: ""}"
        chatMessages.add(ChatMessage(ChatRole.STEP, message))
    }
    private fun publishCycleStages(cycle: ResultadoCiclo) {
        val post = cycle.posExecucao ?: run {
            if (!cycle.aprovado && brainUiStage != BrainUiStage.BLOCKED) brainUiStage = BrainUiStage.FAILED
            return
        }
        fun stage(stage: BrainUiStage, text: String) {
            brainUiStage = stage
            chatMessages.add(ChatMessage(ChatRole.STEP, text))
            appendThreadEvent(ThreadEvent.System(text))
        }
        stage(BrainUiStage.VERIFICANDO, "VERIFICANDO — ${post.verification.status.name}")
        stage(BrainUiStage.CRITICANDO, "CRITICANDO — ${post.critique.status.name}")
        if (post.revision.action == com.brain.behavior.RevisionAction.REVISE) {
            stage(BrainUiStage.REVISE, "REVISE — ${post.revision.reason}")
            stage(BrainUiStage.CORRIGINDO, "CORRIGINDO — ${post.revision.targetCriteria.joinToString().ifBlank { "critérios do Brain" }}")
            if (post.revisionAttempts.isNotEmpty()) stage(BrainUiStage.REEXECUTANDO, "REEXECUTANDO — ${post.revisionAttempts.size} tentativa(s)")
        }
        val evidence = cycle.passos.flatMap { passo ->
            passo.executionEvidence.map { "${passo.passoId}: $it" } + passo.evidencias.map { "${passo.passoId}: ${it}" }
        }.distinct()
        val report = buildString {
            appendLine("Resultado / revisão / readiness")
            appendLine("status=${when {
                post.aprovado -> "PASS"
                post.revision.action == com.brain.behavior.RevisionAction.REVISE -> "REVISE"
                post.readiness.status == com.brain.behavior.ReadinessStatus.BLOCKED -> "BLOCKED"
                else -> "FAILED"
            }}")
            appendLine("verification=${post.verification.status}")
            post.verification.checks.forEach { check -> appendLine("check ${check.criterionId}: ${if (check.passed) "PASS" else "FAIL"} — ${check.detail}${check.evidenceId?.let { " [$it]" } ?: ""}") }
            appendLine("critique=${post.critique.status}")
            post.critique.findings.forEach { finding -> appendLine("finding ${finding.code} (${finding.severity}): ${finding.message}") }
            appendLine("revision=${post.revision.action} — ${post.revision.reason}")
            post.revisionAttempts.forEach { attempt -> appendLine("attempt ${attempt.attempt}: ${attempt.outcome} — ${attempt.evidence}") }
            appendLine("readiness=${post.readiness.status}")
            post.readiness.stages.forEach { stage -> appendLine("readiness ${stage.name}: ${if (stage.passed) "PASS" else "FAIL"}${stage.detail.takeIf { it.isNotBlank() }?.let { " — $it" } ?: ""}${stage.evidenceIds.takeIf { it.isNotEmpty() }?.let { " [${it.joinToString()}]" } ?: ""}") }
            if (post.readiness.blockers.isNotEmpty()) appendLine("blockers=${post.readiness.blockers.joinToString("; ")}")
            appendLine("learningRecorded=${post.learningRecorded}")
            appendLine("evidences=${(post.verification.evidenceIds + evidence).distinct().joinToString().ifBlank { "nenhuma" }}")
            appendLine("artefatos=${cycle.passos.count { it.resultado != null }} resultado(s), ${cycle.passos.sumOf { it.evidencias.size }} evidência(s) de execução")
        }
        appendThreadEvent(ThreadEvent.Report("Resultado / evidências", report))
        stage(if (post.aprovado) BrainUiStage.READY else BrainUiStage.FAILED, if (post.aprovado) "READY" else "FAILED")
    }
    fun clearChat() { chatMessages.clear() }

    fun apiKeyInput(providerId: String): String = apiKeyInputs.getOrPut(providerId) { apiKeyStore.get(providerId).orEmpty() }
    fun updateApiKeyInput(providerId: String, value: String) { apiKeyInputs[providerId] = value }
    fun hasStoredApiKey(providerId: String): Boolean = !apiKeyStore.get(providerId).isNullOrBlank()
    fun saveApiKey(providerId: String) { apiKeyStore.save(providerId, apiKeyInputs[providerId]?.trim().orEmpty()); apiKeyTestState[providerId] = ApiKeyTestUiState.Idle }
    fun testApiKey(providerId: String, model: ApiProviderModel) {
        val key = apiKeyStore.get(providerId)?.takeIf { it.isNotBlank() } ?: run { apiKeyTestState[providerId] = ApiKeyTestUiState.Failure("Cole e salve uma chave antes de testar."); return }
        apiKeyTestState[providerId] = ApiKeyTestUiState.Testing
        viewModelScope.launch { val o = withContext(Dispatchers.IO) { ApiKeyTester.test(model.endpoint, model.id, key) }; apiKeyTestState[providerId] = when (o) { is ApiKeyTestOutcome.Success -> ApiKeyTestUiState.Success("Chave OK — HTTP ${o.statusCode} em ${o.latencyMs} ms"); is ApiKeyTestOutcome.Failure -> ApiKeyTestUiState.Failure(o.message) } }
    }
    fun cancelCommand() {
        chatJob?.cancel(); chatJob = null
        if (chatRunning) { chatRunning = false; brainUiStage = BrainUiStage.FAILED; appendThreadEvent(ThreadEvent.System("Execução cancelada pelo usuário.")) }
        viewModelScope.launch(Dispatchers.IO) { runtime?.cancel() }
    }
    fun runBrainHealthCheck() {
        val c = brainController ?: run { appendThreadEvent(ThreadEvent.System("Verificação indisponível: Brain ainda não inicializado.")); return }
        if (phase != SandboxPhase.Ready) { appendThreadEvent(ThreadEvent.System("Verificação indisponível: sandbox ocupado.")); return }
        viewModelScope.launch {
            phase = SandboxPhase.Running
            val cycle = withContext(Dispatchers.IO) { runCatching { c.healthCheck(factory.persistentSessionId()) }.getOrNull() }
            lastBrainCycle = cycle
            phase = SandboxPhase.Ready
            if (cycle == null) {
                appendThreadEvent(ThreadEvent.System("Verificação pelo Brain falhou ao executar."))
            } else {
                val summary = buildString {
                    appendLine("objetivo=${cycle.objetivo}")
                    appendLine("runId=${cycle.runId}")
                    appendLine("aprovado=${cycle.aprovado}")
                    cycle.passos.forEachIndexed { index, passo ->
                        appendLine("\n--- passo ${index + 1} ---")
                        appendLine("passoId=${passo.passoId}")
                        appendLine("status=${passo.status}")
                        appendLine("decisaoPolicy=${passo.decisaoPolicy}")
                        appendLine("decisaoRouter=${passo.decisaoRouter}")
                        appendLine("execucao=${passo.execucao}")
                        appendLine("evidencias=${passo.evidencias}")
                        appendLine("motivo=${passo.motivo}")
                        appendLine("approvalId=${passo.approvalId}")
                    }
                }
                appendThreadEvent(ThreadEvent.Report("Verificação do Brain", summary))
            }
        }
    }
    fun runTestLab(projectPath: String = "/home/sandbox/workspace") {
        val p = platform ?: run { appendThreadEvent(ThreadEvent.System("TestLab indisponível: sandbox não está pronto.")); return }
        if (phase != SandboxPhase.Ready) { appendThreadEvent(ThreadEvent.System("TestLab indisponível: sandbox ocupado.")); return }
        viewModelScope.launch {
            phase = SandboxPhase.Running
            lastTestLabReport = withContext(Dispatchers.IO) { runCatching { p.testLab.run(projectPath) }.getOrNull() }
            phase = SandboxPhase.Ready
            val report = lastTestLabReport
            appendThreadEvent(
                if (report != null) ThreadEvent.Report("TestLab", "${if (report.success) "PASS" else "FAIL"} — ${report.passed}/${report.steps.size} etapas")
                else ThreadEvent.System("TestLab falhou ao executar.")
            )
        }
    }
    fun runSecurityAssessment() {
        val p = platform ?: run { appendThreadEvent(ThreadEvent.System("Security gate indisponível: sandbox não está pronto.")); return }
        if (phase != SandboxPhase.Ready) { appendThreadEvent(ThreadEvent.System("Security gate indisponível: sandbox ocupado.")); return }
        viewModelScope.launch {
            phase = SandboxPhase.Running
            lastSecurityAssessment = withContext(Dispatchers.IO) {
                runCatching {
                    val root = File(getApplication<Application>().filesDir, "sandbox/workspace")
                    val scan = p.securityScanner.scan(root)
                    p.security.evaluate(scan, p.securityScenarios, emptyList())
                }.getOrNull()
            }
            phase = SandboxPhase.Ready
            val assessment = lastSecurityAssessment
            appendThreadEvent(
                if (assessment != null) ThreadEvent.Report("Security gate", "${if (assessment.readiness.ready) "APROVADO" else "BLOQUEADO"} — ${assessment.findings.size} achado(s)")
                else ThreadEvent.System("Security gate falhou ao executar.")
            )
        }
    }
    fun refreshToolchains() { val p = platform ?: return; viewModelScope.launch(Dispatchers.IO) { val s = com.sandbox.sandbox.BuiltInToolchains.all.associate { it.id to runCatching { p.toolchains.refreshStatus(it.id) }.getOrElse { e -> ToolchainStatus(it.id, com.sandbox.sandbox.ToolchainState.FAILED, error = e.message ?: e.javaClass.simpleName) } }; withContext(Dispatchers.Main) { toolchainStatuses = s } } }
    fun installToolchain(id: String) { val p = platform ?: return; if (phase != SandboxPhase.Ready) return; viewModelScope.launch { phase = SandboxPhase.Running; val s = withContext(Dispatchers.IO) { runCatching { p.toolchains.install(id) }.getOrNull() }; if (s != null) toolchainStatuses = toolchainStatuses + (id to s); phase = SandboxPhase.Ready } }
    fun requestApprovalDemo() {
        val c = brainController ?: run { appendThreadEvent(ThreadEvent.System("Approval demo indisponível: Brain ainda não inicializado.")); return }
        if (phase != SandboxPhase.Ready) { appendThreadEvent(ThreadEvent.System("Approval demo indisponível: sandbox ocupado.")); return }
        viewModelScope.launch {
            phase = SandboxPhase.Running
            val plan = c.approvalDemoPlan(); val runId = "approval-${System.currentTimeMillis()}"
            val cycle = withContext(Dispatchers.IO) { c.executePlan(plan, runId) }
            pendingApprovalPlan = plan; pendingApprovalRunId = runId; pendingApprovalId = cycle.passos.firstOrNull()?.approvalId
            lastBrainCycle = cycle; phase = SandboxPhase.Ready
            pendingApprovalId?.let { appendThreadEvent(ThreadEvent.Approval(it)) } ?: appendThreadEvent(ThreadEvent.System("Approval demo não gerou uma aprovação pendente."))
        }
    }
    /** Fecha o loop de feedback do Prompt Creator: usuário avalia o prompt entregue no chat. */
    fun recordPromptFeedback(actionId: String, positivo: Boolean) {
        brainController?.registrarFeedbackDePrompt(actionId, positivo)
    }

    fun approveAndResume() { val c = brainController ?: return; val plan = pendingApprovalPlan ?: return; val runId = pendingApprovalRunId ?: return; val id = pendingApprovalId ?: return; if (phase != SandboxPhase.Ready) return; viewModelScope.launch { phase = SandboxPhase.Running; lastBrainCycle = withContext(Dispatchers.IO) { c.resumePlan(plan, runId, id) }; pendingApprovalId = null; pendingApprovalPlan = null; pendingApprovalRunId = null; phase = SandboxPhase.Ready } }
    fun refreshWorkspace() { val p = platform ?: return; workspaceProjects = p.workspace.listProjects(); sqliteServiceStatus = p.services.status(BuiltInServices.sqlite("/home/sandbox/workspace")) }
    fun createWorkspaceProject() {
        val p = platform ?: run { appendThreadEvent(ThreadEvent.System("Workspace indisponível: sandbox não está pronto.")); return }
        if (phase != SandboxPhase.Ready) { appendThreadEvent(ThreadEvent.System("Workspace indisponível: sandbox ocupado.")); return }
        workspaceError = null
        runCatching { p.workspace.createProject(workspaceProjectName) }.onSuccess { refreshWorkspace(); appendThreadEvent(ThreadEvent.Report("Workspace", "Projeto criado: $workspaceProjectName")) }.onFailure { workspaceError = it.message ?: "Falha ao criar projeto"; appendThreadEvent(ThreadEvent.System(workspaceError ?: "Falha ao criar projeto")) }
    }
    fun inspectGitStatus() {
        val p = platform ?: run { appendThreadEvent(ThreadEvent.System("Git status indisponível: sandbox não está pronto.")); return }
        val project = workspaceProjects.firstOrNull { it.name == workspaceProjectName } ?: run {
            workspaceError = "Crie ou selecione um projeto antes de consultar o Git."
            appendThreadEvent(ThreadEvent.System(workspaceError ?: "Crie ou selecione um projeto antes de consultar o Git."))
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val s = p.git.status("/home/sandbox/workspace/projects/${project.name}")
            withContext(Dispatchers.Main) { lastGitStatus = s.stdout.ifBlank { s.stderr }; appendThreadEvent(ThreadEvent.Report("Git status", lastGitStatus.orEmpty())) }
        }
    }
    fun startSqliteService() {
        val p = platform ?: run { appendThreadEvent(ThreadEvent.System("SQLite indisponível: sandbox não está pronto.")); return }
        viewModelScope.launch(Dispatchers.IO) { val s = runCatching { p.services.start(BuiltInServices.sqlite("/home/sandbox/workspace")) }.getOrNull(); withContext(Dispatchers.Main) { sqliteServiceStatus = s; appendThreadEvent(ThreadEvent.Report("SQLite start", if (s?.running == true) "serviço ativo (PID ${s.pid})" else "serviço não iniciou")) } }
    }
    fun stopSqliteService() {
        val p = platform ?: run { appendThreadEvent(ThreadEvent.System("SQLite indisponível: sandbox não está pronto.")); return }
        viewModelScope.launch(Dispatchers.IO) { val s = p.services.stop(BuiltInServices.sqlite("/home/sandbox/workspace")); withContext(Dispatchers.Main) { sqliteServiceStatus = s; appendThreadEvent(ThreadEvent.Report("SQLite stop", if (s.running) "serviço ainda ativo" else "serviço parado")) } }
    }
    fun refreshBrainCatalogs() { val i = brainIntegration ?: return; brainSkillSummary = i.enabledSkills().map { "${it.manifest.id} (${it.manifest.capabilities.joinToString()})" }; viewModelScope.launch(Dispatchers.IO) { val r = i.memoryRate(); withContext(Dispatchers.Main) { memorySuccessRate = r } } }
    fun runBrainWorkflow() {
        val i = brainIntegration
        if (i == null) { appendThreadEvent(ThreadEvent.System("Workflow indisponível: Brain ainda não inicializado.")); return }
        if (phase != SandboxPhase.Ready) { appendThreadEvent(ThreadEvent.System("Workflow indisponível: sandbox não está pronto.")); return }
        viewModelScope.launch(Dispatchers.IO) {
            val r = runCatching { i.runHealthWorkflow("workflow-${System.currentTimeMillis()}") }.getOrNull()
            r?.let { i.recordExperience(it.runId, it.status.name == "COMPLETED") }
            withContext(Dispatchers.Main) {
                lastWorkflowStatus = r?.status?.name ?: "FAILED"
                appendThreadEvent(ThreadEvent.Report("Workflow", lastWorkflowStatus ?: "FAILED"))
            }
        }
    }
    fun runDiscovery() {
        val i = brainIntegration ?: run { appendThreadEvent(ThreadEvent.System("Discovery indisponível: Brain ainda não inicializado.")); return }
        viewModelScope.launch {
            discoverySummary = withContext(Dispatchers.IO) {
                runCatching { val r = i.discoverBuiltInCandidate(); "${r.radar.accepted} candidato(s) aceito(s), ${r.radar.rejected} rejeitado(s), ${r.workspace.windows.size} janela(s)" }.getOrElse { "Discovery falhou: ${it.message}" }
            }
            appendThreadEvent(ThreadEvent.Report("Discovery", discoverySummary.orEmpty()))
        }
    }
    fun publishLocalDelivery() {
        val i = brainIntegration ?: run { appendThreadEvent(ThreadEvent.System("Delivery indisponível: Brain ainda não inicializado.")); return }
        if (phase != SandboxPhase.Ready) { appendThreadEvent(ThreadEvent.System("Delivery indisponível: sandbox ocupado.")); return }
        viewModelScope.launch(Dispatchers.IO) {
            val root = File(getApplication<Application>().filesDir, "sandbox/workspace")
            val s = runCatching {
                val runId = "delivery-${System.currentTimeMillis()}"
                val zip = i.packageLocalDelivery(root, runId)
                val digest = java.security.MessageDigest.getInstance("SHA-256").digest(zip.readBytes()).joinToString("") { "%02x".format(it) }
                "ZIP pronto: ${zip.name} (${zip.length()} bytes, sha256 ${digest.take(16)}…)"
            }.getOrElse { "Falha na entrega local: ${it.message ?: "erro desconhecido"}" }
            withContext(Dispatchers.Main) { deliverySummary = s; appendThreadEvent(ThreadEvent.Report("Delivery", s)) }
        }
    }
    fun resetSandbox() { viewModelScope.launch { withContext(Dispatchers.IO) { runtime?.reset { factory.purgeAll() }; runtime = null; brainController = null; brainIntegration = null; factory.clearPersistentSession() }; platform = null; installingComponentIds = emptySet(); statusCache = emptyMap(); pluginSnapshots = emptyList(); pluginHistory = emptyList(); pluginListVersion++; lastResult = null; lastExecution = null; diagnosticsReport = null; lastBrainCycle = null; lastTestLabReport = null; lastSecurityAssessment = null; toolchainStatuses = emptyMap(); pendingApprovalId = null; pendingApprovalPlan = null; pendingApprovalRunId = null; workspaceProjects = emptyList(); lastGitStatus = null; sqliteServiceStatus = null; workspaceError = null; brainSkillSummary = emptyList(); lastWorkflowStatus = null; memorySuccessRate = null; discoverySummary = null; deliverySummary = null; selfCheckReport = null; selfCheckStage = null; selfCheckRunning = false; phase = SandboxPhase.NotReady } }
    fun recentExecutions(limit: Int = 20): List<ExecutionLog> = runtime?.getRecentExecutions(limit) ?: emptyList()
    fun pluginComponents(kind: ComponentKind, query: String, installedOnly: Boolean): List<SandboxComponent> { val base = (platform?.plugins?.components() ?: BuiltInCatalog.all).filter { it.kind == kind }; val searched = if (query.isBlank()) base else base.filter { it.name.contains(query, true) || it.description.contains(query, true) || it.id.contains(query, true) }; return if (!installedOnly) searched else searched.filter { statusCache[it.id]?.state == InstallationState.INSTALLED } }
    fun pluginStatus(id: String): InstalledComponent? = statusCache[id]
    private fun refreshStatusCache() { val p = platform ?: run { statusCache = emptyMap(); brainController?.refreshCapabilities(); return }; viewModelScope.launch(Dispatchers.IO) { val snapshot = p.plugins.components().mapNotNull { c -> p.plugins.status(c.id)?.let { c.id to it } }.toMap(); withContext(Dispatchers.Main) { statusCache = snapshot; brainController?.refreshCapabilities() } } }
    fun installComponent(id: String) { val p = platform ?: run { lastPluginError = "Prepare o sandbox antes de instalar componentes."; return }; if (id in installingComponentIds) return; lastPluginError = null; installingComponentIds = installingComponentIds + id; viewModelScope.launch { var result: Result<InstalledComponent>? = null; try { result = withContext(Dispatchers.IO) { runCatching { p.plugins.install(id) } }; result.onFailure { lastPluginError = it.message ?: "Falha ao instalar $id" }; result.getOrNull()?.let { if (it.state == InstallationState.FAILED) lastPluginError = it.error ?: "Falha ao instalar $id" } } finally { installingComponentIds = installingComponentIds - id; val finished = result; finished?.getOrNull()?.let { statusCache = statusCache + (id to it) }; brainController?.refreshCapabilities(); refreshPluginAudit(); pluginListVersion++ } } }
    fun removeComponent(id: String) { val p = platform ?: run { lastPluginError = "Prepare o sandbox antes de remover componentes."; return }; if (id in installingComponentIds) return; lastPluginError = null; installingComponentIds = installingComponentIds + id; viewModelScope.launch { var result: Result<InstalledComponent?>? = null; try { result = withContext(Dispatchers.IO) { runCatching { p.plugins.remove(id) } }; result.onFailure { lastPluginError = it.message ?: "Falha ao remover $id" }; result.getOrNull()?.let { if (it.state == InstallationState.FAILED) lastPluginError = it.error ?: "Falha ao remover $id" } } finally { installingComponentIds = installingComponentIds - id; val finished = result; finished?.getOrNull()?.let { installed -> statusCache = if (installed != null) statusCache + (id to installed) else statusCache - id }; brainController?.refreshCapabilities(); refreshPluginAudit(); pluginListVersion++ } } }
    fun clearPluginError() { lastPluginError = null }
    fun refreshPluginAudit() { val p = platform ?: return; viewModelScope.launch(Dispatchers.IO) { val snapshots = p.plugins.snapshots(); val history = p.plugins.history(); withContext(Dispatchers.Main) { pluginSnapshots = snapshots; pluginHistory = history } } }
    fun rollbackPlugins(version: Long) { val p = platform ?: return; viewModelScope.launch { runCatching { withContext(Dispatchers.IO) { p.plugins.rollback(version) } }.onFailure { lastPluginError = it.message ?: "Falha ao restaurar snapshot v$version" }; refreshStatusCache(); refreshPluginAudit(); pluginListVersion++ } }
    private fun ExecutionLog.toUiResult() = com.sandbox.runtime.SandboxExecutionResult(stdout = stdout, stderr = stderr, exitCode = exitCode ?: -1, timedOut = timedOut)
}

/** Runtime mínimo somente para journeys instrumentados; não é usado em builds normais. */
private class E2eProcessLauncher(private val workspace: File) : SandboxProcessLauncher {
    override fun launch(command: List<String>, workingDir: String): Process = launch(command, workingDir, true)

    override fun launch(command: List<String>, workingDir: String, networkAllowed: Boolean): Process {
        workspace.mkdirs()
        val normalized = command.mapIndexed { index, value ->
            if (index == 0 && (value == "bash" || value == "/bin/bash")) "/system/bin/sh" else value
        }
        return ProcessBuilder(normalized)
            .directory(workspace)
            .redirectErrorStream(false)
            .apply { environment()["HOME"] = workspace.absolutePath }
            .start()
    }
}
