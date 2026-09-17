package com.brain.planner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TreeOfThoughtsTest {
    @Test
    fun `tarefa simples nao aciona exploracao`() {
        assertFalse(TreeOfThoughts().shouldExplore("adicione um botão vermelho"))
    }

    @Test
    fun `tarefa dificil explora no maximo tres ramos`() {
        val result = TreeOfThoughts().explore("Como migrar a dependência Android sem quebrar a arquitetura e sem perder dados?")
        assertTrue(result.explored.size in 2..3)
        assertEquals(result.explored.maxBy { it.score }, result.selected)
    }
}
