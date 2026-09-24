package com.brain.planner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PlanoExecucaoTest {

    private fun passo(id: String, dependeDe: List<String> = emptyList()) =
        PassoPlano(id = id, capacidade = "sandbox.$id", criterioSucesso = "passo $id ok", dependeDe = dependeDe)

    @Test
    fun `ordem de execucao respeita dependencias`() {
        val plano = PlanoExecucao(
            objetivo = "compilar e testar",
            passos = listOf(
                passo("testar", dependeDe = listOf("compilar")),
                passo("compilar")
            )
        )

        assertEquals(listOf("compilar", "testar"), plano.ordemDeExecucao.map { it.id })
    }

    @Test
    fun `passos independentes mantem ordem estavel de declaracao`() {
        val plano = PlanoExecucao(
            objetivo = "dois passos soltos",
            passos = listOf(passo("b"), passo("a"))
        )

        assertEquals(listOf("b", "a"), plano.ordemDeExecucao.map { it.id })
    }

    @Test
    fun `cadeia de dependencias mais longa fica em ordem`() {
        val plano = PlanoExecucao(
            objetivo = "cadeia",
            passos = listOf(
                passo("c", dependeDe = listOf("b")),
                passo("a"),
                passo("b", dependeDe = listOf("a"))
            )
        )

        assertEquals(listOf("a", "b", "c"), plano.ordemDeExecucao.map { it.id })
    }

    @Test
    fun `ids duplicados sao rejeitados na construcao`() {
        assertThrows(IllegalArgumentException::class.java) {
            PlanoExecucao(objetivo = "x", passos = listOf(passo("a"), passo("a")))
        }
    }

    @Test
    fun `dependencia para id inexistente e rejeitada`() {
        assertThrows(IllegalArgumentException::class.java) {
            PlanoExecucao(objetivo = "x", passos = listOf(passo("a", dependeDe = listOf("fantasma"))))
        }
    }

    @Test
    fun `dependencia ciclica e rejeitada`() {
        assertThrows(IllegalStateException::class.java) {
            PlanoExecucao(
                objetivo = "ciclo",
                passos = listOf(
                    passo("a", dependeDe = listOf("b")),
                    passo("b", dependeDe = listOf("a"))
                )
            )
        }
    }

    @Test
    fun `passo nao pode depender de si mesmo`() {
        assertThrows(IllegalArgumentException::class.java) {
            passo("a", dependeDe = listOf("a"))
        }
    }
}
