package com.sandbox.runtime

enum class SandboxState { NEW, READY, RUNNING, STOPPING, FAILED, CLOSED }

enum class TerminationReason {
    PROCESS_EXIT, TIMEOUT, CANCELLED, SHUTDOWN, START_FAILED, RUNTIME_ERROR, INTERRUPTED, RESOURCE_LIMIT
}

enum class RuntimeEventType {
    ROOTFS_NOT_FOUND, PROOT_NOT_FOUND, PROOT_START_FAILED, ROOTFS_EXTRACTION_FAILED,
    STREAM_READ_ERROR, PROCESS_TIMEOUT, PROCESS_CANCELLED, PROCESS_FORCED_KILL,
    RUNTIME_CLOSE, RUNTIME_RESET, PERSISTENCE_ERROR,
    // O ulimit efetivo (RLIMIT_* real, ver ProotResourceLimits.kt) não bateu
    // com o pedido — normalmente um /bin/bash minimalista do rootfs sem
    // suporte a alguma flag. Isso é uma falha de isolamento, não um detalhe
    // de log: memory_mb/cpu_seconds/etc. configurados podem não estar
    // valendo de verdade para essa execução.
    RESOURCE_LIMIT_UNVERIFIED,
    // finish() lançou uma exceção não tratada depois que o processo já tinha
    // sido iniciado (fora do try/catch de start). Sem este evento, a falha
    // ficava muda: o runtime simplesmente travava em RUNNING e o motivo real
    // nunca chegava a lugar nenhum.
    RUNTIME_ERROR_UNHANDLED_FINISH
}

data class ExecutionLog(
    val executionId: String,
    val sessionId: String,
    val command: List<String>,
    val workingDir: String,
    val startedAt: Long,
    val finishedAt: Long,
    val durationMs: Long,
    val exitCode: Int?,
    val terminationReason: TerminationReason,
    val timedOut: Boolean,
    val forcedKill: Boolean,
    val stdout: String,
    val stderr: String,
    val sandboxState: SandboxState,
    val outputTruncated: Boolean = stdout.contains("[output truncated]") || stderr.contains("[output truncated]")
) {
    val succeeded: Boolean get() = terminationReason == TerminationReason.PROCESS_EXIT && exitCode == 0
}

data class SessionLog(
    val sessionId: String,
    val startedAt: Long,
    val lastExecutionAt: Long,
    val executionCount: Int
)

data class RuntimeEvent(
    val timestamp: Long,
    val type: RuntimeEventType,
    val executionId: String? = null,
    val detail: String? = null
)

interface ExecutionLogRepository {
    fun save(log: ExecutionLog)
    fun get(executionId: String): ExecutionLog?
    fun recent(sessionId: String? = null, limit: Int = 50): List<ExecutionLog>
    fun markInterruptedRunning(executionId: String, now: Long = System.currentTimeMillis()): ExecutionLog?
    fun recoverRunning(sessionId: String, now: Long = System.currentTimeMillis()): List<ExecutionLog>
    fun clearHistory()
}

interface SandboxProcessLauncher {
    fun launch(command: List<String>, workingDir: String): Process

    /**
     * Variante com política de rede. Launchers antigos continuam compatíveis;
     * launchers reais devem sobrescrever para aplicar isolamento ou falhar
     * fechado quando [networkAllowed] for false.
     */
    fun launch(command: List<String>, workingDir: String, networkAllowed: Boolean): Process =
        launch(command, workingDir)

    /** True when the launcher starts the workload in its own POSIX process group. */
    val processGroupManaged: Boolean get() = false

    /**
     * Limites de RLIMIT que este launcher já embutiu no comando lançado
     * (ver ProotResourceLimits.kt). Default `NONE` para não quebrar
     * launchers de teste/host que não passam por `proot` e não emitem o
     * marcador de verificação — nesse caso [ResourceLimitVerification]
     * simplesmente não tem nada para checar.
     */
    val resourceLimits: ProotResourceLimits get() = ProotResourceLimits.NONE
}
