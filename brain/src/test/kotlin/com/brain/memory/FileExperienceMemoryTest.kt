package com.brain.memory

import java.nio.file.Files
import java.time.Instant
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import org.junit.Assert.assertEquals
import org.junit.Test

class FileExperienceMemoryTest {
    private fun experiencia(id: String, resultado: ResultadoExperiencia = ResultadoExperiencia.SUCESSO) = Experiencia(
        id = id, tarefaId = "task-1", problema = "objetivo", estrategiaUsada = "LOCAL",
        promptUsado = null, resultado = resultado, custoEstimado = 0.0, tempoTotalMs = 10,
        erros = emptyList(), registradoEm = Instant.parse("2026-09-12T15:00:00Z")
    )

    @Test
    fun `recupera experiencias apos reabrir arquivo`() = suspendTest {
        val file = Files.createTempFile("brain-memory", ".jsonl").toFile()
        try {
            FileExperienceMemory(file).registrar(experiencia("e-1"))
            assertEquals(1, FileExperienceMemory(file).buscarPorTarefa("task-1").size)
        } finally { file.delete() }
    }

    @Test
    fun `id repetido e idempotente`() = suspendTest {
        val file = Files.createTempFile("brain-memory", ".jsonl").toFile()
        try {
            val memory = FileExperienceMemory(file)
            memory.registrar(experiencia("e-1"))
            memory.registrar(experiencia("e-1", ResultadoExperiencia.FALHA))
            assertEquals(1, memory.buscarPorTarefa("task-1").size)
            assertEquals(1.0, memory.taxaSucessoPorEstrategia("LOCAL"), 0.001)
        } finally { file.delete() }
    }

    private fun suspendTest(block: suspend () -> Unit) {
        var failure: Throwable? = null
        block.startCoroutine(object : Continuation<Unit> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(result: Result<Unit>) { failure = result.exceptionOrNull() }
        })
        failure?.let { throw it }
    }
}
