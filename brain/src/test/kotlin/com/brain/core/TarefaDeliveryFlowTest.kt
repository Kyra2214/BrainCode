package com.brain.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TarefaDeliveryFlowTest {
    @Test
    fun `tarefa percorre ciclo bounded até QA e permite correção`() {
        assertTrue(TarefaStateMachine.transicaoValida(TarefaStatus.PENDENTE, TarefaStatus.PROMPT_GERADO))
        assertTrue(TarefaStateMachine.transicaoValida(TarefaStatus.PROMPT_GERADO, TarefaStatus.EM_EXECUCAO))
        assertTrue(TarefaStateMachine.transicaoValida(TarefaStatus.EM_EXECUCAO, TarefaStatus.AGUARDANDO_QA))
        assertTrue(TarefaStateMachine.transicaoValida(TarefaStatus.AGUARDANDO_QA, TarefaStatus.APROVADA))
        assertTrue(TarefaStateMachine.transicaoValida(TarefaStatus.REPROVADA, TarefaStatus.PROMPT_GERADO))
        assertFalse(TarefaStateMachine.transicaoValida(TarefaStatus.APROVADA, TarefaStatus.EM_EXECUCAO))
    }
}
