package com.brain.core

import com.brain.validation.ValidationResult
import com.brain.validation.ValidationStatus
import kotlin.test.Test
import kotlin.test.assertEquals

class RoadmapValidationCoordinatorTest {
    private fun roadmap() = Roadmap(
        ProjectIntent(UserRequest("criar app"), "app android", "android", Complexity.BAIXA, emptyList(), true),
        listOf(Fase("fase", listOf(Modulo("mod", listOf(Submodulo("sub", listOf(Tarefa("t1", "tarefa"))))))))
    )
    @Test
    fun pass_aprova_tarefa_responsavel() {
        val result = ValidationResult(ValidationStatus.PASS, "c", "cap", taskId = "t1")
        assertEquals(TarefaStatus.APROVADA, RoadmapValidationCoordinator.apply(roadmap(), result).fases[0].modulos[0].submodulos[0].tarefas[0].statusAtual)
    }
    @Test
    fun fail_reprova_tarefa_responsavel() {
        val result = ValidationResult(ValidationStatus.FAIL, "c", "cap", taskId = "t1")
        assertEquals(TarefaStatus.REPROVADA, RoadmapValidationCoordinator.apply(roadmap(), result).fases[0].modulos[0].submodulos[0].tarefas[0].statusAtual)
    }
    @Test
    fun needs_input_nao_avanca_tarefa() {
        val result = ValidationResult(ValidationStatus.NEEDS_INPUT, "c", "cap", taskId = "t1")
        assertEquals(TarefaStatus.PENDENTE, RoadmapValidationCoordinator.apply(roadmap(), result).fases[0].modulos[0].submodulos[0].tarefas[0].statusAtual)
    }
}