package com.brain.planner

import kotlin.coroutines.startCoroutine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FunctionSplitterTest {
    @Test
    fun `splitter divide aplicativo em funcoes com capabilities distintas`() {
        val functions = KeywordFunctionSplitter().split("pesquisar referências, criar aplicativo, compilar e testar")

        assertEquals(listOf("pesquisar", "produzir", "executar"), functions.map { it.id })
        assertEquals(listOf("network.research", "workspace.write", "sandbox.code"), functions.map { it.capacidade })
        assertEquals(listOf(emptyList<String>(), listOf("pesquisar"), listOf("pesquisar", "produzir")), functions.map { it.dependeDe })
        assertTrue(functions.all { it.capacidade.isNotBlank() })
    }

    @Test
    fun `pedido de prompt sobre codigo nao adiciona passo de execucao de codigo`() {
        val functions = KeywordFunctionSplitter().split(
            "crie um prompt para um código de uma interface de um chatbox com login senha e email"
        )

        assertEquals(listOf("prompt.library.write"), functions.map { it.capacidade })
    }

    @Test
    fun `planner usa splitter injetado sem executar`() = suspendPlan {
        var called = false
        val planner = KeywordPlanner(FunctionSplitter { objective ->
            called = true
            listOf(PassoPlano("custom", "custom.capability", "custom ok"))
        })

        val plan = planner.planejar("objetivo")

        assertTrue(called)
        assertEquals("custom.capability", plan.passos.single().capacidade)
    }

    private fun suspendPlan(block: suspend () -> Unit) {
        var failure: Throwable? = null
        block.startCoroutine(object : kotlin.coroutines.Continuation<Unit> {
            override val context = kotlin.coroutines.EmptyCoroutineContext
            override fun resumeWith(result: Result<Unit>) { failure = result.exceptionOrNull() }
        })
        failure?.let { throw it }
    }
}
