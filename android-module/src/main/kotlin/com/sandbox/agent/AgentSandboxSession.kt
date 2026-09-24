package com.sandbox.agent

import com.brain.policy.ExecutionAuthorization
import com.sandbox.runtime.ExecutionLog
import com.sandbox.runtime.ManagedSandboxRuntime
import com.sandbox.runtime.NamespaceSupport
import java.io.File
import java.time.Instant

/**
 * Sessão de trabalho do agente dentro do terreno preparado pela Policy.
 *
 * O caminho de produção usa [rodarCapacidade], que resolve apenas capacidades
 * presentes no catálogo. O método de comando bruto permanece somente como
 * API interna de teste/infraestrutura e não é acessível por consumidores de
 * outros módulos.
 */
class AgentSandboxSession(
    private val authorization: ExecutionAuthorization,
    private val runtime: ManagedSandboxRuntime,
    private val workspaceHostDir: File,
    private val workspaceGuestPath: String,
    private val capabilityResolver: CapabilityResolver = CapabilityResolver(),
    private val clock: () -> Instant = Instant::now,
    private val namespaceSupport: NamespaceSupport = NamespaceSupport.detect()
) : AutoCloseable {

    enum class Status { OPEN, EXPIRED, BUDGET_EXCEEDED, CLOSED }

    sealed interface CommandOutcome {
        data class Completed(val log: ExecutionLog) : CommandOutcome
        data class Refused(val reason: String) : CommandOutcome
    }

    sealed interface FileOutcome<out T> {
        data class Ok<T>(val value: T) : FileOutcome<T>
        data class Refused(val reason: String) : FileOutcome<Nothing>
    }

    @Volatile private var status: Status = Status.OPEN
    private var cpuMillisUsed: Long = 0L
    private var outputBytesUsed: Long = 0L
    private val lock = Any()

    val sessionStatus: Status get() = status
    val runId: String get() = authorization.runId
    val taskId: String get() = authorization.taskId
    val workspaceHostPath: File get() = workspaceHostDir

    init {
        workspaceHostDir.mkdirs()
        require(workspaceHostDir.isDirectory) {
            "workspace precisa ser um diretório: ${workspaceHostDir.path}"
        }
    }

    /**
     * Única API de execução destinada ao agente: a capacidade já autorizada
     * é resolvida contra o catálogo, e seus parâmetros são validados pelo
     * CapabilityResolver antes de chegar ao runtime.
     */
    fun rodarCapacidade(parametros: List<String> = emptyList(), timeoutSeconds: Long = 120): CommandOutcome {
        checkGate()?.let { return CommandOutcome.Refused(it) }
        return when (val resolution = capabilityResolver.resolve(authorization.capability, parametros)) {
            is CapabilityResolver.Resolution.Refused -> CommandOutcome.Refused(resolution.reason)
            is CapabilityResolver.Resolution.Comando -> rodarComandoInterno(resolution.argv, timeoutSeconds)
        }
    }

    /**
     * Escape hatch removido da superfície pública. O runtime não deve ser
     * alimentado por texto/argv arbitrário vindo de outro módulo: toda
     * execução normal passa pelo catálogo de capacidades.
     */
    internal fun rodarComandoInterno(comando: List<String>, timeoutSeconds: Long = 120): CommandOutcome {
        synchronized(lock) {
            checkGate()?.let { return CommandOutcome.Refused(it) }
            if (authorization.networkAllowed && namespaceSupport.compatibilityMode) {
                return CommandOutcome.Refused(
                    "rede recusada: o kernel não oferece user namespaces; " +
                        "isolamento de rede OS-level indisponível"
                )
            }
            if (comando.isEmpty()) return CommandOutcome.Refused("comando vazio")
            val log = runtime.execute(comando, timeoutSeconds = timeoutSeconds, workingDir = workspaceGuestPath, networkAllowed = authorization.networkAllowed)
            cpuMillisUsed += log.durationMs
            outputBytesUsed += log.stdout.toByteArray().size + log.stderr.toByteArray().size
            enforceBudgetAfter()
            return CommandOutcome.Completed(log)
        }
    }

    fun lerArquivo(caminhoRelativo: String): FileOutcome<String> {
        checkGate()?.let { return FileOutcome.Refused(it) }
        val file = resolveInsideWorkspace(caminhoRelativo)
            ?: return FileOutcome.Refused("caminho fora do workspace: $caminhoRelativo")
        if (!file.isFile) return FileOutcome.Refused("arquivo não encontrado: $caminhoRelativo")
        return FileOutcome.Ok(file.readText())
    }

    fun escreverArquivo(caminhoRelativo: String, conteudo: String): FileOutcome<Unit> {
        checkGate()?.let { return FileOutcome.Refused(it) }
        val file = resolveInsideWorkspace(caminhoRelativo)
            ?: return FileOutcome.Refused("caminho fora do workspace: $caminhoRelativo")
        val bytes = conteudo.toByteArray()
        authorization.budget.maxArtifactBytes?.let { max ->
            if (bytes.size > max) return FileOutcome.Refused("arquivo excede maxArtifactBytes ($max)")
        }
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        return FileOutcome.Ok(Unit)
    }

    fun listarArquivos(caminhoRelativo: String = "."): FileOutcome<List<String>> {
        checkGate()?.let { return FileOutcome.Refused(it) }
        val dir = resolveInsideWorkspace(caminhoRelativo)
            ?: return FileOutcome.Refused("caminho fora do workspace: $caminhoRelativo")
        if (!dir.isDirectory) return FileOutcome.Refused("não é um diretório: $caminhoRelativo")
        return FileOutcome.Ok(dir.list()?.sorted().orEmpty())
    }

    override fun close() {
        status = Status.CLOSED
    }

    private fun checkGate(): String? {
        when (status) {
            Status.CLOSED -> return "sessão fechada"
            Status.EXPIRED -> return "autorização expirada em ${authorization.expiresAt}"
            Status.BUDGET_EXCEEDED -> return "orçamento da sessão esgotado"
            Status.OPEN -> Unit
        }
        if (Instant.parse(authorization.expiresAt).isBefore(clock())) {
            status = Status.EXPIRED
            return "autorização expirada em ${authorization.expiresAt}"
        }
        return null
    }

    private fun enforceBudgetAfter() {
        val cpuLimit = authorization.budget.maxCpuMillis
        val outputLimit = authorization.budget.maxOutputBytes
        if ((cpuLimit != null && cpuMillisUsed > cpuLimit) ||
            (outputLimit != null && outputBytesUsed > outputLimit)
        ) status = Status.BUDGET_EXCEEDED
    }

    private fun resolveInsideWorkspace(relativo: String): File? {
        val root = workspaceHostDir.canonicalFile
        val target = File(workspaceHostDir, relativo).canonicalFile
        return if (target == root || target.path.startsWith(root.path + File.separator)) target else null
    }
}
