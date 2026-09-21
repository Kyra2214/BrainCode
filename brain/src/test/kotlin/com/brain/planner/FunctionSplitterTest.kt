package com.brain.planner

import kotlin.coroutines.startCoroutine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun `nega pesquisa nas formas nao sem e evite`() {
        assertEquals(listOf("network.research"), KeywordFunctionSplitter().split("pesquise referências").map { it.capacidade })
        listOf("não pesquise referências", "sem pesquisar referências", "evite pesquisar referências").forEach { objetivo ->
            assertFalse(objetivo, "network.research" in KeywordFunctionSplitter().split(objetivo).map { it.capacidade })
        }
    }

    @Test
    fun `nega producao e execucao sem remover autorizacoes positivas`() {
        assertFalse("workspace.write" in KeywordFunctionSplitter().split("não crie um documento").map { it.capacidade })
        assertFalse("workspace.write" in KeywordFunctionSplitter().split("sem escrever código").map { it.capacidade })
        assertTrue("sandbox.code" in KeywordFunctionSplitter().split("execute os testes").map { it.capacidade })
        assertFalse("sandbox.code" in KeywordFunctionSplitter().split("não execute os testes").map { it.capacidade })
        assertFalse("sandbox.code" in KeywordFunctionSplitter().split("sem executar").map { it.capacidade })
    }

    @Test
    fun `combinacoes preservam acoes positivas e bloqueiam as negadas`() {
        val pesquisaSemExecucao = KeywordFunctionSplitter().split("pesquise e não execute")
        assertEquals(listOf("network.research"), pesquisaSemExecucao.map { it.capacidade })

        val execucaoSemPesquisa = KeywordFunctionSplitter().split("não pesquise e execute o teste local")
        assertEquals(listOf("sandbox.code"), execucaoSemPesquisa.map { it.capacidade })

        val pesquisaProducaoSemExecucao = KeywordFunctionSplitter().split("pesquise, produza o artefato, mas não execute")
        assertEquals(listOf("network.research", "workspace.write"), pesquisaProducaoSemExecucao.map { it.capacidade })
    }

    @Test
    fun `regressao do chat nao cria plano executavel para acoes negadas`() = suspendPlan {
        val plan = KeywordPlanner().planejar(
            "Explique, sem pesquisar na internet e sem executar nenhuma ação, qual skill do Agent Skills seria apropriada"
        )

        assertEquals(listOf("brain.analyze"), plan.passos.map { it.capacidade })
        assertFalse(plan.passos.any { it.capacidade == "network.research" })
        assertFalse(plan.passos.any { it.capacidade == "workspace.write" })
        assertFalse(plan.passos.any { it.capacidade == "sandbox.code" })
    }

    @Test
    fun `perguntas factuais de tempo real geram pesquisa`() {
        assertTrue(KeywordFunctionSplitter().split("qual a temperatura de rio das ostras hoje").any { it.capacidade == "network.research" })
        assertTrue(KeywordFunctionSplitter().split("quanto está o dólar agora").any { it.capacidade == "network.research" })
    }

    @Test
    fun `pergunta conceitual nao gera pesquisa obrigatoria`() {
        assertFalse(KeywordFunctionSplitter().split("me explica como funciona recursão").any { it.capacidade == "network.research" })
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
