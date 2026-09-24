package com.brain.execution

/**
 * Contrato agnóstico entre Brain e Sandbox.
 *
 * O Brain descreve objetivo, capacidades e limites. Ele nunca envia comandos
 * de shell nem recebe autoridade implícita sobre o ambiente de execução.
 * O executor concreto permanece congelado nesta fase.
 */
data class Job(
    val jobId: String,
    val tarefaId: String,
    val sessionId: String,
    val runId: String,
    val objetivo: String,
    val requisitos: List<Requisito>,
    val contexto: JobContext,
    val timeoutSegundos: Int,
    val idempotencyKey: String,
    val riskClass: RiskClass,
    val approvalRequired: Boolean,
    val budget: ResourceBudget,
    val cancellation: CancellationPolicy = CancellationPolicy(),
    val secretRefs: List<String> = emptyList(),
    val capabilitiesRequired: List<String> = requisitos.map { it.capacidade },
    val artifactManifest: ArtifactManifest = ArtifactManifest()
) {
    init {
        require(jobId.isNotBlank()) { "jobId não pode ser vazio" }
        require(sessionId.isNotBlank()) { "sessionId não pode ser vazio" }
        require(runId.isNotBlank()) { "runId não pode ser vazio" }
        require(idempotencyKey.isNotBlank()) { "idempotencyKey não pode ser vazio" }
        require(objetivo.isNotBlank()) { "objetivo não pode ser vazio" }
        require(timeoutSegundos > 0) { "timeoutSegundos deve ser positivo" }
        require(capabilitiesRequired.containsAll(requisitos.map { it.capacidade })) {
            "capabilitiesRequired deve conter todos os requisitos do job"
        }
    }
}

/** Capacidade necessária, nunca um binário ou comando específico. */
data class Requisito(val capacidade: String) {
    init { require(capacidade.isNotBlank()) { "capacidade não pode ser vazia" } }
}

data class JobContext(
    val arquivosEntrada: List<String> = emptyList(),
    val variaveis: Map<String, String> = emptyMap(),
    val networkAllowed: Boolean = false,
    val filesystemRoots: List<String> = emptyList()
)

/**
 * READ_ONLY e LOW são as únicas classes isentas de sandbox obrigatório na
 * Policy (ver com.brain.policy.PolicyBroker, espelhando
 * reference/braincode-python/brain_runtime/policy.py). READ_ONLY foi
 * adicionado nesta junção para preservar essa distinção — o enum original
 * do BraimCode Kotlin só tinha LOW/MEDIUM/HIGH/CRITICAL.
 */
enum class RiskClass { READ_ONLY, LOW, MEDIUM, HIGH, CRITICAL }

data class ResourceBudget(
    val maxCpuMillis: Long? = null,
    val maxMemoryBytes: Long? = null,
    val maxOutputBytes: Long? = null,
    val maxArtifactBytes: Long? = null
) {
    init {
        listOf(maxCpuMillis, maxMemoryBytes, maxOutputBytes, maxArtifactBytes)
            .filterNotNull().forEach { require(it > 0) { "limites de budget devem ser positivos" } }
    }
}

data class CancellationPolicy(
    val cancellable: Boolean = true,
    val deadlineEpochMillis: Long? = null
)

data class ArtifactManifest(
    val expectedPaths: List<String> = emptyList(),
    val allowedExtensions: List<String> = emptyList()
)

enum class JobStatus { SUCESSO, FALHA, TIMEOUT, CANCELADO, REQUISITO_INDISPONIVEL }

data class JobResult(
    val jobId: String,
    val tarefaId: String,
    val runId: String,
    val status: JobStatus,
    val saida: String,
    val erro: String? = null,
    val exitCode: Int? = null,
    val artefatos: List<Artifact> = emptyList(),
    val tempoExecucaoMs: Long,
    val diagnosticos: List<String> = emptyList()
) {
    init {
        require(jobId.isNotBlank()) { "jobId não pode ser vazio" }
        require(runId.isNotBlank()) { "runId não pode ser vazio" }
        require(tempoExecucaoMs >= 0) { "tempoExecucaoMs não pode ser negativo" }
    }
}

data class Artifact(
    val path: String,
    val sha256: String? = null,
    val sizeBytes: Long? = null
) {
    init {
        require(path.isNotBlank()) { "path do artefato não pode ser vazio" }
        sizeBytes?.let { require(it >= 0) { "sizeBytes não pode ser negativo" } }
    }
}

enum class SandboxEventType {
    JOB_STARTED,
    JOB_APPROVAL_REQUESTED,
    JOB_COMPLETED,
    JOB_FAILED,
    JOB_CANCELLED
}

data class SandboxEvent(
    val eventId: String,
    val eventType: SandboxEventType,
    val jobId: String,
    val runId: String,
    val sessionId: String,
    val timestampEpochMillis: Long,
    val sequence: Long,
    val payload: Map<String, String> = emptyMap()
) {
    init {
        require(eventId.isNotBlank()) { "eventId não pode ser vazio" }
        require(jobId.isNotBlank()) { "jobId não pode ser vazio" }
        require(runId.isNotBlank()) { "runId não pode ser vazio" }
        require(sessionId.isNotBlank()) { "sessionId não pode ser vazio" }
        require(sequence >= 0) { "sequence não pode ser negativa" }
    }
}

/** Executor real continua congelado até Policy, EventStore e QA estarem estáveis. */
interface SandboxExecutor {
    suspend fun executar(job: Job): JobResult
}
