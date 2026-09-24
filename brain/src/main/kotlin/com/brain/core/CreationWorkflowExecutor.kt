package com.brain.core

import com.brain.capability.CapabilityRegistry
import com.brain.capability.SpecialistDefinitions
import com.brain.events.BrainEvent
import com.brain.events.EventStore
import com.brain.prompt.PromptGenerator
import com.brain.prompt.PromptLibrary
import com.brain.secretary.CreatePhase

/**
 * Executor de workflow de criação (Porta 3).
 * Chama TarefaStateMachine.transicaoValida() para validar transições.
 * Gera prompts para cada fase usando especialistas.
 * Registra eventos em EventStore.
 */

data class TarefaExecution(
    val tarefaId: String,
    val status: TarefaStatus,
    val prompt: String?,
    val resultado: String?,
    val especialistaId: String,
    val tentativas: Int = 0
)

class CreationWorkflowExecutor(
    private val capabilityRegistry: CapabilityRegistry,
    private val eventStore: EventStore,
    private val promptGenerator: PromptGenerator,
    private val promptLibrary: PromptLibrary,
    private val sessionId: String = "creation-workflow"
) {

    /**
     * Executa plano de workflow com máquina de estado.
     * `runId` correlaciona os eventos desta execução com o restante do run (ver
     * convenção em EventStoreTraceSink: mesmo runId usado pelo ActionAuditLog).
     * Para cada tarefa:
     * 1. Valida transição de estado (TarefaStateMachine)
     * 2. Gera prompt específico da fase
     * 3. Registra evento
     */
    suspend fun executePlan(
        plan: CreationWorkflowPlan,
        faseAtual: CreatePhase,
        runId: String
    ): List<TarefaExecution> {
        val executions = mutableListOf<TarefaExecution>()

        // Registrar início da execução
        appendEvent(
            runId = runId,
            taskId = faseAtual.name,
            type = "workflow.execution.started",
            payload = mapOf(
                "faseAtual" to faseAtual.name,
                "totalTarefas" to plan.tasks.size.toString()
            )
        )

        // PromptGenerator opera em lote sobre o Roadmap inteiro (ver PromptGenerator.kt),
        // não por tarefa isolada — gerar uma vez e indexar por tarefaId.
        val promptsPorTarefa = promptGenerator.gerarPromptsPorRoadmap(plan.roadmap, promptLibrary)
            .associateBy { it.tarefaId }

        for ((index, tarefa) in plan.tasks.withIndex()) {
            try {
                // Validar transição
                val novoStatus = inferStatusFromFase(faseAtual)
                if (!TarefaStateMachine.transicaoValida(tarefa.statusAtual, novoStatus)) {
                    appendEvent(
                        runId = runId,
                        taskId = tarefa.id,
                        type = "workflow.execution.transition_invalid",
                        payload = mapOf(
                            "tarefaId" to tarefa.id,
                            "de" to tarefa.statusAtual.name,
                            "para" to novoStatus.name
                        )
                    )
                    continue
                }

                // Gerar prompt para esta tarefa
                val assignment = plan.assignments.find { it.taskId == tarefa.id }
                    ?: error("assignment não encontrado para tarefa: ${tarefa.id}")

                // O especialista precisa estar registrado e prover a capability atribuída pelo planner.
                val registered = capabilityRegistry.getById(assignment.specialistId)
                    ?: error("especialista não registrado no CapabilityRegistry: ${assignment.specialistId}")
                check(registered.provides(assignment.capability)) {
                    "especialista ${assignment.specialistId} não provê ${assignment.capability}"
                }

                val specialistDef = SpecialistDefinitions.getAllSpecialists()
                    .find { it.capabilityId == assignment.specialistId }
                    ?: error("especialista não encontrado: ${assignment.specialistId}")

                val promptGerado = promptsPorTarefa[tarefa.id]
                    ?: error("prompt não encontrado para tarefa: ${tarefa.id}")

                val prompt = withSpecialistContext(promptGerado.texto, specialistDef, tarefa)

                // Criar execução
                val execution = TarefaExecution(
                    tarefaId = tarefa.id,
                    status = novoStatus,
                    prompt = prompt,
                    resultado = null,
                    especialistaId = assignment.specialistId,
                    tentativas = 1
                )

                executions.add(execution)

                // Registrar evento
                appendEvent(
                    runId = runId,
                    taskId = tarefa.id,
                    type = "workflow.tarefa.prompt_generated",
                    payload = mapOf(
                        "tarefaId" to tarefa.id,
                        "especialista" to specialistDef.nome,
                        "fase" to faseAtual.name,
                        "promptLength" to prompt.length.toString()
                    )
                )
            } catch (e: Exception) {
                appendEvent(
                    runId = runId,
                    taskId = tarefa.id,
                    type = "workflow.tarefa.generation_failed",
                    payload = mapOf(
                        "tarefaId" to tarefa.id,
                        "erro" to (e.message ?: "erro desconhecido")
                    )
                )
            }
        }

        return executions
    }

    /**
     * Avança uma execução na [TarefaStateMachine] (transição inválida lança IllegalStateException)
     * e registra `workflow.tarefa.status_changed`. Devolve a execução atualizada.
     */
    fun advance(
        execution: TarefaExecution,
        novoStatus: TarefaStatus,
        runId: String,
        resultado: String? = execution.resultado
    ): TarefaExecution {
        check(TarefaStateMachine.transicaoValida(execution.status, novoStatus)) {
            "transição inválida: ${execution.status} → $novoStatus"
        }
        appendEvent(
            runId = runId,
            taskId = execution.tarefaId,
            type = "workflow.tarefa.status_changed",
            payload = mapOf(
                "tarefaId" to execution.tarefaId,
                "de" to execution.status.name,
                "para" to novoStatus.name,
                "especialista" to execution.especialistaId
            )
        )
        return execution.copy(status = novoStatus, resultado = resultado)
    }

    /**
     * REPROVADA → PROMPT_GERADO: novo prompt de correção (reusa PromptGenerator.gerarPromptDeCorrecao)
     * com o mesmo contexto do especialista. Conta uma nova tentativa.
     */
    suspend fun regenerateForCorrection(
        execution: TarefaExecution,
        tarefa: Tarefa,
        motivo: String,
        runId: String
    ): TarefaExecution {
        val specialist = SpecialistDefinitions.getAllSpecialists().find { it.capabilityId == execution.especialistaId }
            ?: error("especialista não encontrado: ${execution.especialistaId}")
        val correcao = promptGenerator.gerarPromptDeCorrecao(tarefa, motivo, promptLibrary)
        val avancada = advance(execution, TarefaStatus.PROMPT_GERADO, runId, resultado = null)
        return avancada.copy(
            prompt = withSpecialistContext(correcao.texto, specialist, tarefa),
            tentativas = execution.tentativas + 1
        )
    }

    /** Ponto público de escrita de eventos do workflow (mesma convenção de [appendEvent]). */
    fun record(runId: String, taskId: String, type: String, payload: Map<String, String>) =
        appendEvent(runId, taskId, type, payload)

    /**
     * Antepõe ao prompt já gerado (reuso/template via PromptGenerator) o contexto de
     * responsabilidades do especialista responsável — o PromptGenerator real não aceita
     * um parâmetro de especialista, então essa composição é feita aqui.
     */
    private fun withSpecialistContext(
        textoBase: String,
        specialist: com.brain.capability.SpecialistDefinition,
        tarefa: Tarefa
    ): String {
        val cabecalho = """
            |Responsabilidades do ${specialist.nome}:
            |${specialist.responsabilidades.joinToString("\n") { "- $it" }}
            |
            |Entrada esperada: ${specialist.entrada}
            |Saída esperada: ${specialist.saida}
            |Dependências da tarefa: ${tarefa.dependencies.joinToString(", ") { if (it.isBlank()) "nenhuma" else it }}
        """.trimMargin()

        return "$cabecalho\n\n$textoBase"
    }

    /**
     * Infer novo status de tarefa baseado na fase.
     */
    private fun inferStatusFromFase(fase: CreatePhase): TarefaStatus = when (fase) {
        CreatePhase.APPROVED -> TarefaStatus.PROMPT_GERADO
        CreatePhase.EXECUTION -> TarefaStatus.EM_EXECUCAO
        CreatePhase.INTEGRATION -> TarefaStatus.AGUARDANDO_QA
        CreatePhase.REVIEW -> TarefaStatus.AGUARDANDO_QA
        CreatePhase.TESTS -> TarefaStatus.AGUARDANDO_QA
        else -> TarefaStatus.PENDENTE
    }

    /**
     * Valida se todas as tarefas podem transicionar para o novo status.
     */
    fun validateTransitions(
        tarefas: List<Tarefa>,
        novoStatus: TarefaStatus
    ): Boolean {
        return tarefas.all { tarefa ->
            TarefaStateMachine.transicaoValida(tarefa.statusAtual, novoStatus)
        }
    }

    /**
     * Registra resultado de execução de tarefa.
     */
    suspend fun recordTarefaResult(
        execution: TarefaExecution,
        resultado: String,
        novoStatus: TarefaStatus,
        runId: String
    ) {
        if (!TarefaStateMachine.transicaoValida(execution.status, novoStatus)) {
            error("transição inválida: ${execution.status} → $novoStatus")
        }

        appendEvent(
            runId = runId,
            taskId = execution.tarefaId,
            type = "workflow.tarefa.completed",
            payload = mapOf(
                "tarefaId" to execution.tarefaId,
                "status" to novoStatus.name,
                "resultadoLength" to resultado.length.toString()
            )
        )
    }

    /** Único ponto de escrita no EventStore real: mesma convenção usada em
     * EventStoreTraceSink / RuntimeDoctorImpl (sequence = tamanho atual do log). */
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
}
