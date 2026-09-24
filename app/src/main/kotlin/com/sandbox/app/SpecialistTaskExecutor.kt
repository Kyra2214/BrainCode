package com.sandbox.app

import com.brain.capability.CapabilityDefinition
import com.brain.gateway.ActionExecution
import com.brain.gateway.ActionExecutor
import com.brain.gateway.ActionRequest
import com.brain.policy.PolicyDecision
import com.brain.router.PapelPipeline

/**
 * Executor de `specialist.execute`: envia o prompt de uma tarefa da Porta 3 ao provider autorizado.
 * Só devolve texto (entregável em markdown) — não grava arquivos nem roda comandos; quem constrói é
 * `workspace.generate`. Parâmetros: 0 = prompt da tarefa, 1 = id do especialista, 2 = id da tarefa.
 */
class SpecialistTaskExecutor(private val gateway: BrainApiGateway) : ActionExecutor {
    override fun execute(request: ActionRequest, capability: CapabilityDefinition, decision: PolicyDecision): ActionExecution {
        val taskPrompt = request.parameters["parameter.0"]?.trim().orEmpty()
        val specialistId = request.parameters["parameter.1"]?.trim().orEmpty()
        if (taskPrompt.isBlank() || specialistId.isBlank()) {
            return ActionExecution(false, error = "prompt ou especialista ausente", provenance = provenance(capability))
        }
        val prompt = """
            Você atua como o especialista $specialistId de um projeto de software.
            Responda somente com o entregável da tarefa, em markdown, sem executar ações e sem pedir confirmações.

            $taskPrompt
        """.trimIndent()
        return try {
            val response = gateway.complete(prompt, papelFor(specialistId), decision.authorizedAccountIds)
            ActionExecution(
                success = true,
                result = response.text,
                evidence = listOf("provider:${response.providerId}", "model:${response.modelId}", "specialist:$specialistId"),
                provenance = provenance(capability),
                custo = 0.0
            )
        } catch (error: IllegalStateException) {
            // Sem chave/conta disponível: falha determinística, não vale retry automático.
            ActionExecution(false, error = "Especialista sem provider disponível. ${error.message.orEmpty()}".trim(), provenance = provenance(capability))
        } catch (error: Exception) {
            ActionExecution(false, error = "Falha ao executar especialista: ${error.message ?: error.javaClass.simpleName}", provenance = provenance(capability), retryable = true)
        }
    }

    private fun papelFor(specialistId: String): PapelPipeline = when (specialistId) {
        "agent.code", "agent.backend", "agent.database", "agent.test" -> PapelPipeline.EXECUCAO_CODIGO
        else -> PapelPipeline.PLANEJAMENTO
    }

    private fun provenance(capability: CapabilityDefinition) = listOf("app:SpecialistTaskExecutor", "capability:${capability.id}")
}
