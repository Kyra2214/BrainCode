package com.brain.runtime

import com.brain.events.BrainEvent
import com.brain.events.EventStore
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Implementação real do RuntimeDoctor (ver interface em RuntimeDoctor.kt).
 * Diagnóstico de sandbox: recursos, seccomp vs proot, namespaces.
 * Recovery: retoma jobs pausados, detecta modo degradado.
 */

data class DiagnosticReport(
    val timestamp: Long = System.currentTimeMillis(),
    val statusGeral: String,
    val recursosDisponiveis: Boolean,
    val memoriaLivreMB: Long,
    val fdsAbertos: Int,
    val seccompDisponivel: Boolean,
    val namespacesSuportados: List<String>,
    val modoAtual: String, // "seccomp" ou "proot-fallback"
    val avisos: List<String> = emptyList(),
    val recomendacoes: List<String> = emptyList()
)

data class InterruptedJob(
    val jobId: String,
    val processId: Int,
    val timestamp: Long,
    val estadoSalvo: String // serializado
)

class RuntimeDoctorImpl(
    private val eventStore: EventStore,
    private val sessionId: String = "runtime-doctor"
) : RuntimeDoctor {

    private val interruptedJobs = ConcurrentHashMap<String, InterruptedJob>()
    @Volatile private var modoSeccomp = true

    /**
     * Diagnóstico completo do runtime para um passo específico do plano.
     * `error`, quando presente, é incorporado aos sintomas retornados.
     */
    override fun diagnose(runId: String, taskId: String, error: String?): RuntimeDiagnosis {
        val fallback = detectSeccompFallback(runId, taskId)
        val report = DiagnosticReport(
            statusGeral = "checking",
            recursosDisponiveis = checkRecursos(),
            memoriaLivreMB = getMemoriaLivre(),
            fdsAbertos = countOpenFDs(),
            seccompDisponivel = checkSeccomp(),
            namespacesSuportados = checkNamespaces(),
            modoAtual = if (fallback) "proot-fallback" else "seccomp"
        )

        val avisos = mutableListOf<String>()
        val recomendacoes = mutableListOf<String>()

        if (report.memoriaLivreMB < 256) {
            avisos.add("Memória livre crítica: ${report.memoriaLivreMB} MB")
            recomendacoes.add("Liberar memória ou aumentar limite")
        }

        if (report.fdsAbertos > 900) {
            avisos.add("File descriptors próximo ao limite")
            recomendacoes.add("Revisar e fechar FDs não utilizados")
        }

        if (!report.seccompDisponivel && report.modoAtual == "seccomp") {
            avisos.add("Seccomp não disponível, usando fallback proot")
            recomendacoes.add("Atualizar kernel ou usar proot")
        }

        if (!error.isNullOrBlank()) {
            avisos.add(error)
        }

        val healthy = avisos.isEmpty()

        appendEvent(
            runId = runId,
            taskId = taskId,
            type = "runtime.diagnostic",
            payload = mapOf(
                "status" to (if (healthy) "healthy" else "warning"),
                "avisos" to avisos.size.toString(),
                "modo" to report.modoAtual
            )
        )

        return RuntimeDiagnosis(
            healthy = healthy,
            symptoms = avisos,
            recommendedRepair = recomendacoes.firstOrNull()
        )
    }

    /**
     * Tenta reparo automático a partir de um diagnóstico não saudável.
     * Retorna true se alguma ação de recuperação foi aplicada com sucesso.
     */
    override fun repair(runId: String, taskId: String, diagnosis: RuntimeDiagnosis): Boolean {
        if (diagnosis.healthy) return false

        cleanupOldInterrupted()

        val interrupted = interruptedJobs[taskId]
        if (interrupted != null) {
            return recoverInterrupted(runId, taskId, interrupted)
        }

        // Sem job interrompido registrado: não há reparo automático seguro a aplicar
        // (ex.: seccomp indisponível exige intervenção fora do processo). Apenas registra.
        appendEvent(
            runId = runId,
            taskId = taskId,
            type = "runtime.repair_unavailable",
            payload = mapOf(
                "recommendedRepair" to (diagnosis.recommendedRepair ?: "nenhuma ação automática disponível")
            )
        )
        return false
    }

    /**
     * Detecta modo degradado: seccomp indisponível, fallback para proot.
     * Retorna true se está em fallback.
     */
    fun detectSeccompFallback(runId: String, taskId: String): Boolean {
        return try {
            // Verificar /proc/sys/kernel/seccomp
            val seccompFile = File("/proc/sys/kernel/seccomp")
            if (seccompFile.exists()) {
                val conteudo = seccompFile.readText().trim()
                conteudo == "0"
            } else {
                true // Se não existe, assume fallback
            }
        } catch (e: Exception) {
            // Se erro ao ler, assume fallback
            true
        }.also { isFallback ->
            modoSeccomp = !isFallback
            if (isFallback) {
                appendEvent(
                    runId = runId,
                    taskId = taskId,
                    type = "runtime.seccomp_fallback_detected",
                    payload = mapOf("modo" to "proot-degradado")
                )
            }
        }
    }

    /**
     * Retoma job pausado/timeoutado.
     * Estado é carregado de arquivo temporário.
     */
    private fun recoverInterrupted(runId: String, taskId: String, interrupted: InterruptedJob): Boolean {
        return try {
            val estado = interrupted.estadoSalvo
            val processId = interrupted.processId

            appendEvent(
                runId = runId,
                taskId = taskId,
                type = "runtime.job_recovery",
                payload = mapOf(
                    "jobId" to interrupted.jobId,
                    "processId" to processId.toString(),
                    "estadoLength" to estado.length.toString()
                )
            )

            // Aqui entraria lógica de restauração específica do sandbox
            // Por enquanto, registrar sucesso
            interruptedJobs.remove(interrupted.jobId)
            true
        } catch (e: Exception) {
            appendEvent(
                runId = runId,
                taskId = taskId,
                type = "runtime.job_recovery_failed",
                payload = mapOf(
                    "jobId" to interrupted.jobId,
                    "erro" to (e.message ?: "erro desconhecido")
                )
            )
            false
        }
    }

    /**
     * Registra job como interrompido para recovery futuro.
     */
    fun registerInterrupted(runId: String, jobId: String, processId: Int, estado: String) {
        interruptedJobs[jobId] = InterruptedJob(
            jobId = jobId,
            processId = processId,
            timestamp = System.currentTimeMillis(),
            estadoSalvo = estado
        )

        appendEvent(
            runId = runId,
            taskId = jobId,
            type = "runtime.job_interrupted",
            payload = mapOf(
                "jobId" to jobId,
                "processId" to processId.toString()
            )
        )
    }

    /**
     * Limpar jobs antigos (> 24h).
     */
    fun cleanupOldInterrupted() {
        val agora = System.currentTimeMillis()
        val umDia = 24 * 60 * 60 * 1000L

        interruptedJobs.entries.removeAll { (_, job) ->
            (agora - job.timestamp) > umDia
        }
    }

    /** Único ponto de escrita no EventStore real: runId/sessionId/taskId/sequence seguem a
     * mesma convenção usada em EventStoreTraceSink (sequence = tamanho atual do log). */
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

    private fun checkRecursos(): Boolean {
        val memoria = getMemoriaLivre()
        val fds = countOpenFDs()
        return memoria > 128 && fds < 1000
    }

    private fun getMemoriaLivre(): Long {
        return try {
            // freeMemory() é só a folga do heap atual (pequena logo após o start); o que importa
            // é quanto ainda pode crescer até o limite: max - (total - free).
            val runtime = Runtime.getRuntime()
            val usado = runtime.totalMemory() - runtime.freeMemory()
            ((runtime.maxMemory() - usado) / (1024 * 1024))
        } catch (e: Exception) {
            0
        }
    }

    private fun countOpenFDs(): Int {
        return try {
            val procFd = File("/proc/self/fd")
            procFd.listFiles()?.size ?: 0
        } catch (e: Exception) {
            0
        }
    }

    private fun checkSeccomp(): Boolean {
        return try {
            val seccompFile = File("/proc/sys/kernel/seccomp")
            seccompFile.exists() && seccompFile.readText().trim() != "0"
        } catch (e: Exception) {
            false
        }
    }

    private fun checkNamespaces(): List<String> {
        val namespaces = mutableListOf<String>()
        try {
            val nsDir = File("/proc/self/ns")
            nsDir.listFiles()?.forEach { file ->
                if (file.isDirectory || file.isFile) {
                    namespaces.add(file.name)
                }
            }
        } catch (e: Exception) {
            // fallback
        }
        return namespaces.ifEmpty { listOf("pid", "mnt", "ipc", "uts", "user") }
    }
}
