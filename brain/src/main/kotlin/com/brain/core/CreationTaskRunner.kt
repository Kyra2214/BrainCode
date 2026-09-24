package com.brain.core

import kotlinx.coroutines.CancellationException

/** Tarefa entregue ao despachante: o prompt já inclui o contexto das dependências aprovadas. */
data class SpecialistTask(
    val runId: String,
    val tarefa: Tarefa,
    val specialistId: String,
    val capability: String,
    val faseNome: String,
    val prompt: String,
    val attempt: Int
)

data class SpecialistOutcome(
    val success: Boolean,
    val output: String? = null,
    val error: String? = null,
    val evidence: List<String> = emptyList()
)

data class QaVerdict(val approved: Boolean, val reason: String)

/** Quem de fato executa a tarefa (no controller: ActionGateway/PolicyBroker; aqui é só o contrato). */
fun interface SpecialistDispatcher {
    suspend fun dispatch(task: SpecialistTask): SpecialistOutcome
}

fun interface TaskQa {
    fun review(task: SpecialistTask, outcome: SpecialistOutcome): QaVerdict
}

/** QA estrutural mínimo: entrega não vazia. Substitua por um TaskQa mais forte quando houver contrato de validação. */
object NonEmptyOutputQa : TaskQa {
    private const val MIN_CHARS = 20
    override fun review(task: SpecialistTask, outcome: SpecialistOutcome): QaVerdict {
        val text = outcome.output?.trim().orEmpty()
        return if (text.length >= MIN_CHARS) QaVerdict(true, "entrega não vazia")
        else QaVerdict(false, "entrega vazia ou curta demais (${text.length} caracteres)")
    }
}

data class TaskRunResult(
    val tarefaId: String,
    val especialistaId: String,
    val status: TarefaStatus,
    val attempts: Int,
    val resultado: String?,
    val motivo: String? = null,
    val blocked: Boolean = false
) {
    val approved: Boolean get() = status == TarefaStatus.APROVADA
}

/**
 * Consome os prompts gerados pelo [CreationWorkflowExecutor]: percorre o roadmap fase a fase, executa
 * cada tarefa pelo especialista responsável (via [SpecialistDispatcher]) e conduz a máquina de estados
 * PROMPT_GERADO → EM_EXECUCAO → AGUARDANDO_QA → APROVADA/REPROVADA, com até [maxCorrections]
 * novas tentativas usando prompt de correção. Tarefa cuja dependência não foi APROVADA fica bloqueada.
 *
 * Não decide política nem chama provider: isso é responsabilidade do dispatcher injetado.
 */
class CreationTaskRunner(
    private val executor: CreationWorkflowExecutor,
    private val qa: TaskQa = NonEmptyOutputQa,
    private val maxCorrections: Int = 1,
    private val contextCharsPerDependency: Int = 3_000
) {
    init {
        require(maxCorrections >= 0) { "maxCorrections não pode ser negativo" }
        require(contextCharsPerDependency > 0) { "contextCharsPerDependency deve ser positivo" }
    }

    suspend fun run(
        plan: CreationWorkflowPlan,
        prompts: List<TarefaExecution>,
        runId: String,
        dispatcher: SpecialistDispatcher
    ): List<TaskRunResult> {
        val byTask = prompts.associateBy { it.tarefaId }
        val results = linkedMapOf<String, TaskRunResult>()
        executor.record(runId, "roadmap", "workflow.specialists.started", mapOf("tarefas" to plan.tasks.size.toString()))

        for (fase in plan.roadmap.fases) {
            val tarefas = fase.modulos.flatMap { it.submodulos }.flatMap { it.tarefas }
            for (tarefa in tarefas) {
                val assignment = plan.assignments.find { it.taskId == tarefa.id }
                val especialistaId = assignment?.specialistId ?: tarefa.responsibleAgentId ?: "desconhecido"
                val initial = byTask[tarefa.id]
                val blockedBy = tarefa.dependencies.filter { it.isNotBlank() && results[it]?.approved != true }
                when {
                    initial == null || assignment == null -> results[tarefa.id] = blocked(runId, tarefa, especialistaId, TarefaStatus.PENDENTE, "sem prompt gerado ou sem especialista atribuído")
                    blockedBy.isNotEmpty() -> results[tarefa.id] = blocked(runId, tarefa, especialistaId, initial.status, "dependência não aprovada: ${blockedBy.joinToString(",")}")
                    else -> results[tarefa.id] = runTask(plan, tarefa, assignment, fase.nome, initial, results, runId, dispatcher)
                }
            }
        }

        val values = results.values.toList()
        executor.record(
            runId, "roadmap", "workflow.specialists.finished",
            mapOf(
                "aprovadas" to values.count { it.approved }.toString(),
                "reprovadas" to values.count { !it.approved && !it.blocked }.toString(),
                "bloqueadas" to values.count { it.blocked }.toString()
            )
        )
        return values
    }

    private suspend fun runTask(
        plan: CreationWorkflowPlan,
        tarefa: Tarefa,
        assignment: SpecialistAssignment,
        faseNome: String,
        initial: TarefaExecution,
        done: Map<String, TaskRunResult>,
        runId: String,
        dispatcher: SpecialistDispatcher
    ): TaskRunResult {
        var execution = initial
        var lastReason: String? = null
        val maxAttempts = 1 + maxCorrections
        for (attempt in 1..maxAttempts) {
            execution = executor.advance(execution, TarefaStatus.EM_EXECUCAO, runId)
            val task = SpecialistTask(
                runId = runId,
                tarefa = tarefa,
                specialistId = assignment.specialistId,
                capability = assignment.capability,
                faseNome = faseNome,
                prompt = withDependencyContext(execution.prompt.orEmpty(), tarefa, done),
                attempt = attempt
            )
            val outcome = try {
                dispatcher.dispatch(task)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SpecialistOutcome(false, error = e.message ?: e::class.simpleName)
            }
            execution = executor.advance(execution, TarefaStatus.AGUARDANDO_QA, runId, resultado = outcome.output)
            val verdict = if (outcome.success) qa.review(task, outcome)
            else QaVerdict(false, outcome.error ?: "falha do executor")

            if (verdict.approved) {
                execution = executor.advance(execution, TarefaStatus.APROVADA, runId)
                executor.record(runId, tarefa.id, "workflow.tarefa.completed", mapOf(
                    "tarefaId" to tarefa.id, "status" to TarefaStatus.APROVADA.name,
                    "resultadoLength" to (outcome.output?.length ?: 0).toString(), "tentativas" to attempt.toString()
                ))
                return TaskRunResult(tarefa.id, assignment.specialistId, TarefaStatus.APROVADA, attempt, outcome.output)
            }

            lastReason = verdict.reason
            execution = executor.advance(execution, TarefaStatus.REPROVADA, runId)
            executor.record(runId, tarefa.id, "workflow.tarefa.rejected", mapOf(
                "tarefaId" to tarefa.id, "tentativa" to attempt.toString(), "motivo" to verdict.reason
            ))
            if (attempt < maxAttempts) {
                execution = executor.regenerateForCorrection(execution, tarefa, verdict.reason, runId)
            }
        }
        return TaskRunResult(tarefa.id, assignment.specialistId, TarefaStatus.REPROVADA, maxAttempts, execution.resultado, lastReason)
    }

    private fun blocked(runId: String, tarefa: Tarefa, especialistaId: String, status: TarefaStatus, motivo: String): TaskRunResult {
        executor.record(runId, tarefa.id, "workflow.tarefa.blocked", mapOf("tarefaId" to tarefa.id, "motivo" to motivo))
        return TaskRunResult(tarefa.id, especialistaId, status, 0, null, motivo, blocked = true)
    }

    private fun withDependencyContext(prompt: String, tarefa: Tarefa, done: Map<String, TaskRunResult>): String {
        val contexto = tarefa.dependencies.mapNotNull { id -> done[id]?.takeIf { it.approved && !it.resultado.isNullOrBlank() } }
        if (contexto.isEmpty()) return prompt
        val bloco = contexto.joinToString("\n\n") { dep ->
            "### ${dep.tarefaId} (${dep.especialistaId})\n${dep.resultado!!.take(contextCharsPerDependency)}"
        }
        return "$prompt\n\nRESULTADOS DAS TAREFAS ANTERIORES (use como contexto, não repita):\n$bloco"
    }
}
