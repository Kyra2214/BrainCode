package com.brain.skill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RooftsSkillSelectorTest {
    private val catalogo = listOf(
        RooftsSkill(
            id = "test-driven-development",
            description = "Drives development with tests using the red-green-refactor loop.",
            body = "corpo tdd"
        ),
        RooftsSkill(
            id = "frontend-ui-engineering",
            description = "Builds production-quality, accessible, responsive user-facing UIs.",
            body = "corpo frontend"
        ),
        RooftsSkill(
            id = "planning-and-task-breakdown",
            description = "Breaks work into ordered tasks.",
            body = "corpo planejamento"
        ),
        RooftsSkill(
            id = "security-and-hardening",
            description = "Hardens code against vulnerabilities.",
            body = "corpo seguranca"
        )
    )

    @Test
    fun `seleciona skill de UI quando o objetivo fala de tela e interface`() {
        val selecionadas = RooftsSkillSelector.select("Crie uma tela de login com interface responsiva", catalogo)
        assertTrue(selecionadas.any { it.id == "frontend-ui-engineering" })
    }

    @Test
    fun `seleciona skill de seguranca quando o objetivo fala de login e dados sensiveis`() {
        val selecionadas = RooftsSkillSelector.select("Implemente um login seguro que valida senha e dados sensiveis do usuario", catalogo)
        assertTrue(selecionadas.any { it.id == "security-and-hardening" })
    }

    @Test
    fun `seleciona skill de testes quando o objetivo pede cobertura com teste`() {
        val selecionadas = RooftsSkillSelector.select("Corrija o bug e cubra com teste unitario", catalogo)
        assertTrue(selecionadas.any { it.id == "test-driven-development" })
    }

    @Test
    fun `sem nenhum sinal especifico cai no default de planejamento`() {
        val selecionadas = RooftsSkillSelector.select("faca alguma coisa", catalogo)
        assertEquals(listOf("planning-and-task-breakdown"), selecionadas.map { it.id })
    }

    @Test
    fun `objetivo vazio nao seleciona nada`() {
        assertTrue(RooftsSkillSelector.select("", catalogo).isEmpty())
    }

    @Test
    fun `respeita o limite maximo de skills`() {
        val objetivo = "tela de login segura com teste automatizado e plano de tarefas"
        val selecionadas = RooftsSkillSelector.select(objetivo, catalogo, max = 2)
        assertEquals(2, selecionadas.size)
    }
}
