package com.brain.core

import com.brain.secretary.CreatePhase
import com.brain.secretary.DeterministicSecretary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CreationWorkflowPlannerTest {
    @Test
    fun `criação aprovada gera roadmap tarefas e assignments`() {
        val classified = DeterministicSecretary().classify("criar aplicativo Android de notas")
        val approved = classified.copy(phase = CreatePhase.APPROVED, scope = classified.scope.copy(phase = CreatePhase.APPROVED))
        val workflow = CreationWorkflowPlanner.build(approved, listOf("login"))

        assertEquals(6, workflow.tasks.size)
        assertTrue(workflow.roadmap.fases.map { it.nome }.containsAll(listOf("ARCHITECTURE", "EXECUTION", "TESTS", "DELIVERY")))
        assertEquals("android", workflow.roadmap.projectIntent.platform)
        assertTrue(workflow.assignments.any { it.specialistId == "agent.architecture" })
        assertTrue(workflow.assignments.any { it.specialistId == "agent.release" })
    }
}
