package com.brain.core

/**
 * Transições válidas de TarefaStatus (ver Models.kt). Adaptada do
 * ContractStateMachines.taskTransition() do IaBrain
 * (brain/ContractStateMachines.kt) — mesma ideia (só pares de/para
 * explicitamente permitidos viram transição válida), reduzida ao
 * conjunto de status que já existe no Brain novo.
 */
object TarefaStateMachine {

    fun transicaoValida(de: TarefaStatus, para: TarefaStatus): Boolean = when (de) {
        TarefaStatus.PENDENTE -> para == TarefaStatus.PROMPT_GERADO
        // Pode voltar pra PROMPT_GERADO se o prompt gerado for descartado antes de rodar.
        TarefaStatus.PROMPT_GERADO -> para == TarefaStatus.EM_EXECUCAO || para == TarefaStatus.PENDENTE
        TarefaStatus.EM_EXECUCAO -> para == TarefaStatus.AGUARDANDO_QA
        TarefaStatus.AGUARDANDO_QA -> para == TarefaStatus.APROVADA || para == TarefaStatus.REPROVADA
        // Reprovada não volta pro início do zero: volta pro ciclo de correção (novo prompt).
        TarefaStatus.REPROVADA -> para == TarefaStatus.PROMPT_GERADO
        TarefaStatus.APROVADA -> false // estado final
    }
}
