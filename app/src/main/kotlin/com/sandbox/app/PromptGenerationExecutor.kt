package com.sandbox.app

import com.brain.capability.CapabilityDefinition
import com.brain.gateway.ActionExecution
import com.brain.gateway.ActionExecutor
import com.brain.gateway.ActionRequest
import com.brain.policy.PolicyDecision
import com.brain.prompt.PromptLibrary
import com.brain.retrieval.PromptLibraryRetrievalSource
import com.brain.retrieval.Retrieval
import com.brain.retrieval.RetrievalQuery
import com.brain.retrieval.RetrievalStatus
import com.brain.router.PapelPipeline

/**
 * Gera prompts (texto/imagem) respeitando a ordem econômica do Brain:
 * 1) busca na biblioteca local de prompts (retrieval, sem custo/sem rede);
 * 2) só se nada reaproveitável for encontrado, chama a API do provedor configurado;
 * 3) se não houver provedor/chave configurada, devolve erro claro em vez de travar o passo.
 */
class PromptGenerationExecutor(
    private val gateway: BrainApiGateway,
    promptLibrary: PromptLibrary
) : ActionExecutor {
    private val retrieval = Retrieval(listOf(PromptLibraryRetrievalSource.from(promptLibrary)))

    override fun execute(
        request: ActionRequest,
        capability: CapabilityDefinition,
        decision: PolicyDecision
    ): ActionExecution {
        val objective = request.parameters["parameter.0"]?.trim().orEmpty()
        if (objective.isBlank()) {
            return ActionExecution(false, error = "objetivo do prompt ausente", provenance = provenance(capability))
        }

        // 1) Biblioteca local primeiro — reuso antes de qualquer chamada externa.
        val busca = retrieval.retrieve(RetrievalQuery(objective))
        val hit = busca.hit
        if (busca.status == RetrievalStatus.FOUND && hit != null) {
            val confiancaPct = (hit.confidence * 100).toInt()
            return ActionExecution(
                success = true,
                result = "Encontrado na biblioteca local (confiança $confiancaPct%):\n\n${hit.content}",
                evidence = hit.provenance + "retrieval:biblioteca-local",
                provenance = provenance(capability)
            )
        }

        // 2) Nada na biblioteca: só agora recorre à API do provedor configurado.
        return try {
            val prompt = """
                Escreva um único prompt de imagem, detalhado e pronto para uso em um gerador de imagens,
                para o pedido abaixo. Descreva sujeito, composição, iluminação, estilo e nível de realismo.
                Responda somente com o texto do prompt, sem explicações nem marcações extras.

                Pedido:
                $objective
            """.trimIndent()
            val response = gateway.complete(prompt, PapelPipeline.ESCRITA_DE_PROMPT)
            ActionExecution(
                success = true,
                result = "Não encontrado na biblioteca local. Prompt gerado via API:\n\n${response.text}",
                evidence = listOf(
                    "retrieval:biblioteca-local:nao-encontrado",
                    "provider:${response.providerId}",
                    "model:${response.modelId}"
                ),
                provenance = provenance(capability)
            )
        } catch (error: IllegalStateException) {
            ActionExecution(
                false,
                error = "Não encontrado na biblioteca local e nenhuma API configurada. Configure uma chave em Configurações → Provedores. ${error.message.orEmpty()}".trim(),
                provenance = provenance(capability)
            )
        } catch (error: Exception) {
            ActionExecution(false, error = "Falha ao gerar prompt: ${error.message ?: error.javaClass.simpleName}", provenance = provenance(capability))
        }
    }

    private fun provenance(capability: CapabilityDefinition) = listOf("app:PromptGenerationExecutor", "capability:${capability.id}")
}
