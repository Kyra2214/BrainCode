package com.brain.core

import com.brain.capability.CapabilityRegistry
import com.brain.capability.SpecialistCapabilities
import com.brain.events.InMemoryEventStore
import com.brain.prompt.DefaultPromptGenerator
import com.brain.prompt.InMemoryPromptLibrary
import com.brain.secretary.CreatePhase
import com.brain.secretary.DeterministicSecretary
import com.brain.secretary.SecretaryState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class CreationWorkflowExecutorTest {
    private fun plan(): CreationWorkflowPlan {
        val initial = DeterministicSecretary().classify("criar um aplicativo de notas")
        val approved = SecretaryState().designate(initial).approve().currentIntent!!
        return CreationWorkflowPlanner.build(approved, listOf("salvar notas offline"))
    }

    private fun executor(registry: CapabilityRegistry, events: InMemoryEventStore, dir: File) = CreationWorkflowExecutor(
        capabilityRegistry = registry,
        eventStore = events,
        promptGenerator = DefaultPromptGenerator(),
        promptLibrary = InMemoryPromptLibrary(emptyList(), File(dir, "lib.jsonl"))
    )

    @Test
    fun `fase APPROVED gera um prompt por tarefa com contexto do especialista`() = runBlocking {
        val dir = Files.createTempDirectory("cwe-").toFile()
        try {
            val events = InMemoryEventStore()
            val registry = CapabilityRegistry(SpecialistCapabilities.definitions())
            val executions = executor(registry, events, dir).executePlan(plan(), CreatePhase.APPROVED, "run-cwe")

            assertEquals(6, executions.size)
            assertTrue(executions.all { it.status == TarefaStatus.PROMPT_GERADO })
            assertTrue(executions.all { it.prompt!!.contains("Responsabilidades do") })
            assertEquals(6, events.replay("run-cwe").count { it.type == "workflow.tarefa.prompt_generated" })
            assertTrue(events.verifyIntegrity())
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun `especialista nao registrado no CapabilityRegistry falha a tarefa sem lancar`() = runBlocking {
        val dir = Files.createTempDirectory("cwe-empty-").toFile()
        try {
            val events = InMemoryEventStore()
            val executions = executor(CapabilityRegistry(), events, dir).executePlan(plan(), CreatePhase.APPROVED, "run-cwe-empty")

            assertTrue(executions.isEmpty())
            assertEquals(6, events.replay("run-cwe-empty").count { it.type == "workflow.tarefa.generation_failed" })
        } finally { dir.deleteRecursively() }
    }
}
