package com.brain.core

import com.brain.validation.ValidationResult
import com.brain.validation.ValidationStatus

/**
 * Traduz validação técnica em progresso operacional do Roadmap.
 * Não executa tarefas nem autoriza capabilities: apenas aplica o resultado
 * já validado à tarefa responsável.
 */
object RoadmapValidationCoordinator {
    fun apply(roadmap: Roadmap, result: ValidationResult): Roadmap {
        val taskId = result.taskId ?: return roadmap
        val nextStatus = when (result.status) {
            ValidationStatus.PASS -> TarefaStatus.APROVADA
            ValidationStatus.FAIL -> TarefaStatus.REPROVADA
            ValidationStatus.NEEDS_INPUT -> null
        } ?: return roadmap
        return roadmap.copy(
            fases = roadmap.fases.map { fase ->
                fase.copy(
                    modulos = fase.modulos.map { modulo ->
                        modulo.copy(
                            submodulos = modulo.submodulos.map { sub ->
                                sub.copy(tarefas = sub.tarefas.map { tarefa ->
                                    if (tarefa.id == taskId) tarefa.copy(statusAtual = nextStatus) else tarefa
                                })
                            }
                        )
                    }
                )
            }
        )
    }
}
