package com.brain.planner

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import com.brain.router.PapelPipeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeywordPlannerTest {
    @Test fun `objetivo de codigo gera execucao`() = suspendTest {
        val plano = KeywordPlanner().planejar("implementar e testar código")
        assertEquals("executar", plano.ordemDeExecucao.last().id)
        assertTrue(plano.passos.any { it.capacidade == "sandbox.code" })
    }

    @Test fun `conjugacoes de criar aplicativo geram producao`() = suspendTest {
        val plano = KeywordPlanner().planejar("crie um aplicativo de IPTV")
        val produzir = plano.passos.first { it.id == "produzir" }
        assertEquals("workspace.write", produzir.capacidade)
        assertEquals("crie um aplicativo de IPTV", produzir.parametros.single())
    }

    @Test fun `pedido de prompt gera producao mesmo sem verbo de criacao`() = suspendTest {
        val plano = KeywordPlanner().planejar("quero um prompt para uma imagem realista de foguete")
        val produzir = plano.passos.first { it.id == "produzir" }
        assertEquals("produzir", produzir.id)
        assertEquals("prompt.library.write", produzir.capacidade)
        assertEquals(PapelPipeline.ESCRITA_DE_PROMPT, produzir.papel)
        assertTrue(plano.passos.any { it.capacidade == "network.research" })
    }

    @Test fun `transformacao de foto sem palavra prompt gera prompt e nao diagnostico`() = suspendTest {
        val plano = KeywordPlanner().planejar(
            "irei enviar uma foto minha pra ia para ela fazer uma alteração quero que ela pegue a foto e transforme minha imagem em um cavaleiro dos zodíacos de ouro"
        )

        assertEquals("prompt.library.write", plano.passos.last().capacidade)
        assertTrue(plano.passos.none { it.capacidade == "brain.analyze" })
        assertTrue(plano.passos.none { it.capacidade == "sandbox.info" })
    }

    @Test fun `objetivo desconhecido permanece seguro e local`() = suspendTest {
        val plano = KeywordPlanner().planejar("organizar ideias")
        assertEquals("brain.analyze", plano.passos.single().capacidade)
    }

    @Test fun `pedidos de pesquisa mais amplos disparam o passo pesquisar`() = suspendTest {
        val comparar = KeywordPlanner().planejar("compare as abordagens disponíveis atualmente para X")
        assertTrue(comparar.passos.any { it.capacidade == "network.research" })

        val documentacao = KeywordPlanner().planejar("qual é a documentação atual da API X?")
        assertTrue(documentacao.passos.any { it.capacidade == "network.research" })

        val encontrar = KeywordPlanner().planejar("encontre a documentação oficial para implementar X")
        assertTrue(encontrar.passos.any { it.capacidade == "network.research" })
    }

    @Test fun `pedidos comuns de prompt visual pesquisam e codigo nao pesquisa`() = suspendTest {
        val prompt = KeywordPlanner().planejar("crie um prompt de um foguete espacial decolando")
        assertTrue(prompt.passos.any { it.capacidade == "network.research" })

        val codigo = KeywordPlanner().planejar("implementar e testar código")
        assertTrue(codigo.passos.none { it.capacidade == "network.research" })
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
