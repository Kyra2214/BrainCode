package com.brain.workflow

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors


data class WorkflowNode(
    val id: String,
    val capability: String,
    val dependencies: Set<String> = emptySet(),
    val retryLimit: Int = 1,
    val timeoutMs: Long = 60_000L
) {
    init {
        require(id.isNotBlank()) { "id de node obrigatório" }
        require(capability.isNotBlank()) { "capability de node obrigatória" }
        require(retryLimit >= 0) { "retryLimit não pode ser negativo" }
        require(timeoutMs > 0) { "timeoutMs deve ser positivo" }
    }
}

data class WorkflowManifest(
    val id: String,
    val version: String,
    val nodes: List<WorkflowNode>,
    val enabled: Boolean = true,
    val maxParallelism: Int = 1
) {
    init { require(maxParallelism > 0) { "maxParallelism deve ser positivo" } }
}

data class WorkflowStepResult(
    val nodeId: String,
    val success: Boolean,
    val attempts: Int,
    val output: Map<String, Any?> = emptyMap(),
    val error: String? = null,
    val evidence: List<String> = emptyList()
)

data class WorkflowRunResult(
    val runId: String,
    val idempotencyKey: String,
    val status: WorkflowStatus,
    val steps: List<WorkflowStepResult>,
    val error: String? = null
)

enum class WorkflowStatus { RUNNING, COMPLETED, FAILED, CANCELLED, TIMED_OUT }

data class WorkflowLease(val workflowId: String, val owner: String, val fencingToken: String, val expiresAtEpochMs: Long)

/** Persistência simples de lease: um owner ativo impede dois workers simultâneos. */
class WorkflowLeaseStore(private val file: File? = null, private val ttlMs: Long = 60_000) {
    @Synchronized fun acquire(workflowId: String, owner: String): WorkflowLease {
        val current = read()
        check(current == null || current.expiresAtEpochMs <= System.currentTimeMillis() || current.owner == owner) {
            "workflow já possui lease ativo"
        }
        val lease = WorkflowLease(workflowId, owner, UUID.randomUUID().toString(), System.currentTimeMillis() + ttlMs)
        file?.let {
            it.parentFile?.mkdirs()
            it.writeText(JSONObject().put("workflowId", workflowId).put("owner", owner).put("fencingToken", lease.fencingToken).put("expiresAt", lease.expiresAtEpochMs).toString())
        }
        return lease
    }

    @Synchronized fun check(lease: WorkflowLease): Boolean {
        val current = read() ?: return false
        return current.fencingToken == lease.fencingToken && current.owner == lease.owner && current.expiresAtEpochMs > System.currentTimeMillis()
    }

    @Synchronized fun release(lease: WorkflowLease) { if (check(lease)) file?.delete() }

    private fun read(): WorkflowLease? = file?.takeIf { it.isFile }?.let {
        runCatching {
            JSONObject(it.readText()).let { json ->
                WorkflowLease(json.getString("workflowId"), json.getString("owner"), json.getString("fencingToken"), json.getLong("expiresAt"))
            }
        }.getOrNull()
    }
}

/** Executor determinístico com autorização, DAG, retry, timeout e fencing. */
class WorkflowEngine(private val stateFile: File? = null, private val leaseStore: WorkflowLeaseStore? = null) {
    private val lock = Any()
    private val completedRuns = linkedMapOf<String, WorkflowRunResult>()

    init { synchronized(lock) { load() } }

    /** Compatibilidade com consumidores existentes que passam execute como última lambda. */
    fun run(
        manifest: WorkflowManifest,
        runId: String,
        idempotencyKey: String,
        authorize: (String) -> Boolean,
        execute: (WorkflowNode, Int) -> WorkflowStepResult
    ): WorkflowRunResult = run(manifest, runId, idempotencyKey, authorize, execute, { false })

    fun run(
        manifest: WorkflowManifest,
        runId: String,
        idempotencyKey: String,
        authorize: (String) -> Boolean,
        execute: (WorkflowNode, Int) -> WorkflowStepResult,
        isCancelled: () -> Boolean = { false }
    ): WorkflowRunResult = synchronized(lock) {
        completedRuns[idempotencyKey]?.takeIf { it.status in setOf(WorkflowStatus.COMPLETED, WorkflowStatus.FAILED, WorkflowStatus.CANCELLED, WorkflowStatus.TIMED_OUT) }?.let { return it }
        validate(manifest)
        if (!manifest.enabled) throw IllegalStateException("workflow desabilitado")
        val lease = leaseStore?.acquire(manifest.id, runId)
        try {
            manifest.nodes.forEach { node -> if (!authorize(node.capability)) throw SecurityException("capability não autorizada: ${node.capability}") }
            val results = mutableListOf<WorkflowStepResult>()
            val done = mutableSetOf<String>()
            val pending = manifest.nodes.toMutableList()
            while (pending.isNotEmpty()) {
                check(lease == null || leaseStore!!.check(lease)) { "lease/fencing expirado" }
                if (isCancelled()) return@synchronized persist(WorkflowRunResult(runId, idempotencyKey, WorkflowStatus.CANCELLED, results, "workflow cancelado"))
                val ready = pending.filter { node -> node.dependencies.all { it in done } }.take(manifest.maxParallelism)
                if (ready.isEmpty()) {
                    return@synchronized persist(WorkflowRunResult(runId, idempotencyKey, WorkflowStatus.FAILED, results, "dependência não concluída"))
                }
                val batchResults = executeBatch(ready, manifest.maxParallelism, execute, isCancelled)
                results += batchResults
                persist(WorkflowRunResult(runId, idempotencyKey, WorkflowStatus.RUNNING, results.toList()))
                pending.removeAll(ready.toSet())
                if (batchResults.any { it.error == "cancelled" }) {
                    return@synchronized persist(WorkflowRunResult(runId, idempotencyKey, WorkflowStatus.CANCELLED, results, "workflow cancelado"))
                }
                val timeout = batchResults.firstOrNull { it.error?.startsWith("timeout") == true }
                if (timeout != null) {
                    return@synchronized persist(WorkflowRunResult(runId, idempotencyKey, WorkflowStatus.TIMED_OUT, results, timeout.error))
                }
                val failed = batchResults.firstOrNull { !it.success }
                if (failed != null) {
                    return@synchronized persist(WorkflowRunResult(runId, idempotencyKey, WorkflowStatus.FAILED, results, failed.error))
                }
                done += ready.map { it.id }
            }
            persist(WorkflowRunResult(runId, idempotencyKey, WorkflowStatus.COMPLETED, results))
        } finally { lease?.let { leaseStore?.release(it) } }
    }

    /**
     * Executa um WORKFLOW.md sem interpretar o Markdown como código.
     * O caller transforma o corpo em uma instrução para o gateway autorizado;
     * esta camada aplica manifest, lease, retry, timeout e idempotência.
     */
    fun runDocument(
        document: WorkflowDocument,
        runId: String,
        idempotencyKey: String,
        authorize: (String) -> Boolean,
        executeBody: (String, WorkflowNode, Int) -> WorkflowStepResult,
        isCancelled: () -> Boolean = { false }
    ): WorkflowRunResult = run(
        manifest = document.executionManifest("workflow.run"),
        runId = runId,
        idempotencyKey = idempotencyKey,
        authorize = authorize,
        execute = { node, attempt -> executeBody(document.body, node, attempt) },
        isCancelled = isCancelled
    )

    fun result(idempotencyKey: String): WorkflowRunResult? = synchronized(lock) { completedRuns[idempotencyKey] }

    private fun executeBatch(
        nodes: List<WorkflowNode>,
        maxParallelism: Int,
        execute: (WorkflowNode, Int) -> WorkflowStepResult,
        isCancelled: () -> Boolean
    ): List<WorkflowStepResult> {
        if (nodes.size == 1 || maxParallelism == 1) return nodes.map { executeNode(it, execute, isCancelled) }
        val pool = Executors.newFixedThreadPool(minOf(nodes.size, maxParallelism))
        return try {
            nodes.map { node -> pool.submit<WorkflowStepResult> { executeNode(node, execute, isCancelled) } }.map { it.get() }
        } finally { pool.shutdownNow() }
    }

    private fun executeNode(
        node: WorkflowNode,
        execute: (WorkflowNode, Int) -> WorkflowStepResult,
        isCancelled: () -> Boolean
    ): WorkflowStepResult {
        var finalResult: WorkflowStepResult? = null
        for (attempt in 1..(node.retryLimit + 1)) {
            if (isCancelled()) return WorkflowStepResult(node.id, false, attempt, error = "cancelled")
            val started = System.nanoTime()
            val result = runCatching { execute(node, attempt) }
                .getOrElse { WorkflowStepResult(node.id, false, attempt, error = it.message ?: "falha desconhecida") }
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            finalResult = if (elapsedMs > node.timeoutMs) {
                result.copy(nodeId = node.id, attempts = attempt, success = false, error = "timeout após ${elapsedMs}ms", evidence = result.evidence + "timeout:${node.id}")
            } else {
                result.copy(nodeId = node.id, attempts = attempt)
            }
            if (finalResult.success) break
        }
        return finalResult ?: WorkflowStepResult(node.id, false, 0, error = "nenhuma tentativa executada")
    }

    private fun validate(manifest: WorkflowManifest) {
        require(manifest.id.isNotBlank() && manifest.version.isNotBlank()) { "manifesto inválido" }
        require(manifest.nodes.map { it.id }.toSet().size == manifest.nodes.size) { "ids de nodes duplicados" }
        val ids = manifest.nodes.map { it.id }.toSet()
        require(manifest.nodes.all { it.dependencies.all(ids::contains) }) { "dependência de workflow inválida" }
        val visiting = mutableSetOf<String>(); val visited = mutableSetOf<String>()
        fun visit(id: String) {
            check(id !in visiting) { "ciclo no workflow" }
            if (!visited.add(id)) return
            visiting += id
            manifest.nodes.first { it.id == id }.dependencies.forEach(::visit)
            visiting -= id
        }
        manifest.nodes.forEach { visit(it.id) }
    }

    private fun persist(result: WorkflowRunResult): WorkflowRunResult {
        completedRuns[result.idempotencyKey] = result
        stateFile?.let { file ->
            file.parentFile?.mkdirs()
            file.writeText(
                JSONObject()
                    .put("version", 2)
                    .put("runId", result.runId)
                    .put("idempotencyKey", result.idempotencyKey)
                    .put("status", result.status.name)
                    .put("error", result.error ?: JSONObject.NULL)
                    .put("updatedAt", Instant.now().toString())
                    .put("steps", JSONArray(result.steps.map { step ->
                        JSONObject().put("nodeId", step.nodeId).put("success", step.success).put("attempts", step.attempts)
                            .put("error", step.error ?: JSONObject.NULL).put("evidence", JSONArray(step.evidence))
                    })).toString()
            )
        }
        return result
    }

    private fun load() {
        val file = stateFile ?: return
        if (!file.exists()) return
        runCatching {
            val json = JSONObject(file.readText())
            val steps = json.optJSONArray("steps") ?: JSONArray()
            val results = (0 until steps.length()).map { i ->
                val item = steps.getJSONObject(i)
                val evidence = item.optJSONArray("evidence")?.let { array -> (0 until array.length()).map(array::getString) }.orEmpty()
                WorkflowStepResult(item.getString("nodeId"), item.getBoolean("success"), item.getInt("attempts"), error = item.optString("error").takeUnless { it == "null" }, evidence = evidence)
            }
            val status = WorkflowStatus.valueOf(json.getString("status"))
            completedRuns[json.getString("idempotencyKey")] = WorkflowRunResult(json.getString("runId"), json.getString("idempotencyKey"), status, results, json.optString("error").takeUnless { it == "null" })
        }
    }
}
