package com.sandbox.app

import com.brain.capability.CapabilityDefinition
import com.brain.gateway.ActionExecution
import com.brain.gateway.ActionExecutor
import com.brain.gateway.ActionRequest
import com.brain.policy.PolicyDecision
import com.brain.prompt.PromptLibrary
import com.brain.prompt.PromptTemplate
import com.brain.retrieval.PromptLibraryRetrievalSource
import com.brain.retrieval.Retrieval
import com.brain.retrieval.RetrievalQuery
import com.brain.retrieval.RetrievalStatus
import com.brain.router.PapelPipeline
import kotlinx.coroutines.runBlocking
import java.util.Locale

/**
 * Fluxo operacional de criação/melhoria de prompts:
 * 1) procura primeiro na biblioteca local;
 * 2) só considera reutilizável um candidato com similaridade >= 50%;
 * 3) adapta o template ao pedido atual antes de devolvê-lo;
 * 4) se não houver candidato compatível, usa a API especialista em prompt;
 * 5) TODO prompt produzido por IA é salvo na biblioteca ANTES de ser devolvido;
 * 6) se não houver API/chave válida, retorna erro explícito ao usuário.
 */
class PromptGenerationExecutor(
    private val gateway: BrainApiGateway,
    private val promptLibrary: PromptLibrary
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

        // Biblioteca primeiro. O Retrieval entrega candidatos ordenados, mas a
        // decisão de compatibilidade é feita aqui por similaridade do pedido,
        // não pela taxa histórica de sucesso do template.
        val busca = retrieval.retrieve(RetrievalQuery(objective))
        val hit = busca.hit
        if (busca.status == RetrievalStatus.FOUND && hit != null) {
            val similaridade = similarity(objective, hit.content)
            if (similaridade >= MIN_SIMILARITY) {
                val adapted = adaptPrompt(hit.content, objective)
                val confiancaPct = (similaridade * 100).toInt()
                return ActionExecution(
                    success = true,
                    result = "Encontrei um prompt compatível na biblioteca (similaridade $confiancaPct%) e adaptei ao seu pedido:\n\n$adapted",
                    evidence = hit.provenance + "retrieval:biblioteca-local" + "similarity:${"%.2f".format(Locale.US, similaridade)}",
                    provenance = provenance(capability)
                )
            }
        }

        // Sem prompt local compatível: a criação passa para o especialista/API.
        return try {
            val prompt = """
                Você é o especialista em engenharia de prompts de imagem do BrainCode.
                Transforme o pedido abaixo em um único prompt de imagem detalhado e coerente,
                pronto para uso em um gerador de imagens.
                Preserve a intenção do usuário e acrescente somente detalhes que tornem a
                descrição visual mais clara: sujeito, composição, ambiente, iluminação,
                câmera, materiais, estilo e nível de realismo quando forem pertinentes.
                Responda somente com o prompt final, sem explicações nem marcações extras.

                Pedido do usuário:
                $objective
            """.trimIndent()

            val response = gateway.complete(prompt, PapelPipeline.ESCRITA_DE_PROMPT)
            val generated = response.text.trim()
            if (generated.isBlank()) {
                return ActionExecution(
                    false,
                    error = "A API especialista não devolveu um prompt válido.",
                    provenance = provenance(capability)
                )
            }

            // Regra estrutural: toda saída de IA que virar prompt passa pela
            // biblioteca ANTES de ser devolvida ao usuário.
            saveGeneratedPrompt(objective, generated)

            ActionExecution(
                success = true,
                result = "Não encontrei prompt compatível na biblioteca. Criei um novo com a API especialista e salvei na biblioteca:\n\n$generated",
                evidence = listOf(
                    "retrieval:biblioteca-local:nao-encontrado-ou-abaixo-de-50",
                    "provider:${response.providerId}",
                    "model:${response.modelId}",
                    "prompt-library:saved-before-response"
                ),
                provenance = provenance(capability)
            )
        } catch (error: IllegalStateException) {
            ActionExecution(
                false,
                error = "A biblioteca não possui prompt compatível (mínimo 50%) e não há API especialista com chave válida registrada. Configure uma API em Configurações → Provedores para criar o prompt.",
                provenance = provenance(capability)
            )
        } catch (error: Exception) {
            ActionExecution(false, error = "Falha ao gerar prompt: ${error.message ?: error.javaClass.simpleName}", provenance = provenance(capability))
        }
    }

    private fun similarity(objective: String, candidate: String): Double {
        val requested = tokenize(objective)
        if (requested.isEmpty()) return 0.0
        val candidateTokens = tokenize(candidate)
        val overlap = requested.count { it in candidateTokens }
        return overlap.toDouble() / requested.size.toDouble()
    }

    private fun adaptPrompt(template: String, objective: String): String {
        var adapted = template
        val replacements = mapOf(
            "{pedido}" to objective,
            "{objetivo}" to objective,
            "{descricao}" to objective,
            "{{pedido}}" to objective,
            "{{objetivo}}" to objective,
            "{{descricao}}" to objective
        )
        replacements.forEach { (placeholder, value) -> adapted = adapted.replace(placeholder, value, ignoreCase = true) }
        if (tokenize(adapted).intersect(tokenize(objective)).isEmpty()) {
            adapted = "$adapted\n\nContexto específico do pedido: $objective"
        }
        return adapted.trim()
    }

    private fun saveGeneratedPrompt(objective: String, generated: String) {
        runBlocking {
            promptLibrary.salvarNovaVersao(
                PromptTemplate(
                    id = "generated-${System.currentTimeMillis()}",
                    versao = 1,
                    finalidade = "geração e melhoria de prompt de imagem",
                    contextoDeUso = objective,
                    skillRelacionada = "prompt-generation",
                    agenteRelacionado = "prompt-specialist",
                    textoTemplate = generated,
                    taxaSucesso = 1.0,
                    custoMedio = 0.0,
                    tempoMedioMs = 0L,
                    historicoMelhorias = listOf("gerado por API e salvo antes da resposta ao usuário")
                )
            )
        }
    }

    private fun tokenize(text: String): Set<String> = text.lowercase(Locale.ROOT)
        .split(Regex("[^\\p{L}\\p{N}]+"))
        .filter { it.length > 2 }
        .toSet()

    private fun provenance(capability: CapabilityDefinition) = listOf("app:PromptGenerationExecutor", "capability:${capability.id}")

    private companion object {
        const val MIN_SIMILARITY = 0.50
    }
}
