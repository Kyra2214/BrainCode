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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class CreationTaskRunnerTest {
    private class Env {
        val dir: File = Files.createTempDirectory("ctr-").toFile()
        val events = InMemoryEventStore()
        val executor = CreationWorkflowExecutor(
            CapabilityRegistry(SpecialistCapabilities.definitions()), events, DefaultPromptGenerator(),
            InMemoryPromptLibrary(emptyList(), File(dir, "lib.jsonl"))
        )
        val plan: CreationWorkflowPlan = kotlin.run {
            val initial = DeterministicSecretary().classify("criar um aplicativo de notas")
            CreationWorkflowPlanner.build(SecretaryState().designate(initial).approve().currentIntent!!, listOf("salvar notas"))
        }
        fun prompts() = runBlocking { executor.executePlan(plan, CreatePhase.APPROVED, "run") }
        fun runAll(qa: TaskQa = NonEmptyOutputQa, corrections: Int = 1, dispatcher: SpecialistDispatcher): List<TaskRunResult> =
            runBlocking { CreationTaskRunner(executor, qa, corrections).run(plan, prompts(), "run", dispatcher) }
        fun close() { dir.deleteRecursively() }
    }

    private fun ok(text: String = "entrega válida com conteúdo suficiente") = SpecialistOutcome(true, output = text)

    @Test
    fun `executa em ordem de roadmap, encadeia resultados e aprova todas`() {
        val env = Env()
        try {
            val prompts = mutableMapOf<String, String>()
            val order = mutableListOf<String>()
            val results = env.runAll { task ->
                order += task.tarefa.id; prompts[task.tarefa.id] = task.prompt
                ok("saida-de-${task.tarefa.id} com conteúdo suficiente")
            }

            assertEquals(listOf("requirements", "architecture", "implementation", "integration", "tests", "delivery"), order)
            assertTrue(results.all { it.approved })
            assertTrue(prompts.getValue("architecture").contains("saida-de-requirements"))
            assertFalse(prompts.getValue("requirements").contains("RESULTADOS DAS TAREFAS ANTERIORES"))
            val changes = env.events.replay("run").filter { it.type == "workflow.tarefa.status_changed" && it.taskId == "delivery" }
            assertEquals(listOf("PROMPT_GERADO→EM_EXECUCAO", "EM_EXECUCAO→AGUARDANDO_QA", "AGUARDANDO_QA→APROVADA"),
                changes.map { "${it.payload["de"]}→${it.payload["para"]}" })
            assertTrue(env.events.verifyIntegrity())
        } finally { env.close() }
    }

    @Test
    fun `reprovada e reexecutada com prompt de correcao e aprovada na segunda tentativa`() {
        val env = Env()
        try {
            val promptsArch = mutableListOf<String>()
            val results = env.runAll { task ->
                if (task.tarefa.id == "architecture") {
                    promptsArch += task.prompt
                    if (task.attempt == 1) SpecialistOutcome(false, error = "timeout do provider") else ok()
                } else ok()
            }

            val arch = results.single { it.tarefaId == "architecture" }
            assertTrue(arch.approved)
            assertEquals(2, arch.attempts)
            assertTrue(promptsArch[1].contains("CORREÇÃO NECESSÁRIA"))
            assertTrue(promptsArch[1].contains("timeout do provider"))
            assertTrue(results.all { it.approved })
        } finally { env.close() }
    }

    @Test
    fun `falha definitiva bloqueia dependentes sem despachar`() {
        val env = Env()
        try {
            val dispatched = mutableListOf<String>()
            val results = env.runAll(corrections = 1) { task ->
                dispatched += task.tarefa.id
                if (task.tarefa.id == "requirements") SpecialistOutcome(false, error = "sempre falha") else ok()
            }

            assertEquals(listOf("requirements", "requirements"), dispatched) // 1 tentativa + 1 correção
            val first = results.first()
            assertEquals(TarefaStatus.REPROVADA, first.status)
            assertEquals("sempre falha", first.motivo)
            assertTrue(results.drop(1).all { it.blocked })
            assertEquals(5, env.events.replay("run").count { it.type == "workflow.tarefa.blocked" })
        } finally { env.close() }
    }

    @Test
    fun `QA reprova entrega curta mesmo com sucesso do executor`() {
        val env = Env()
        try {
            val results = env.runAll(corrections = 0) { SpecialistOutcome(true, output = "ok") }
            assertEquals(TarefaStatus.REPROVADA, results.first().status)
            assertTrue(results.first().motivo!!.contains("curta"))
        } finally { env.close() }
    }

    @Test
    fun `excecao do despachante vira reprovacao e nao propaga`() {
        val env = Env()
        try {
            val results = env.runAll(corrections = 0) { throw IllegalStateException("gateway caiu") }
            assertEquals("gateway caiu", results.first().motivo)
            assertTrue(results.drop(1).all { it.blocked })
        } finally { env.close() }
    }
}
