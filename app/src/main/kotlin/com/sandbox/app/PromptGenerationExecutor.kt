package com.sandbox.app

import com.brain.capability.CapabilityDefinition
import com.brain.gateway.ActionExecution
import com.brain.gateway.ActionExecutor
import com.brain.gateway.ActionRequest
import com.brain.policy.PolicyDecision
import com.brain.prompt.PromptLibrary
import com.brain.prompt.PromptOutcomeTracker
import com.brain.prompt.PromptOutcomeTrackers
import com.brain.prompt.PromptSimilarity
import com.brain.prompt.PromptTemplate
import com.brain.router.PapelPipeline
import kotlinx.coroutines.runBlocking
import java.util.Locale

/** Fluxo: biblioteca -> compatibilidade -> adaptação; somente depois API -> persistência -> resultado real. */
class PromptGenerationExecutor(
    private val gateway: BrainApiGateway,
    private val promptLibrary: PromptLibrary,
    private val outcomeTracker: PromptOutcomeTracker = PromptOutcomeTrackers.forLibrary(promptLibrary)
) : ActionExecutor {
    override fun execute(request: ActionRequest, capability: CapabilityDefinition, decision: PolicyDecision): ActionExecution {
        val startedAt = System.nanoTime()
        val objective = request.parameters["parameter.0"]?.trim().orEmpty()
        if (objective.isBlank()) return ActionExecution(false, error = "objetivo do prompt ausente", provenance = provenance(capability))

        val candidato = runBlocking {
            promptLibrary.buscarPorContexto(objective)
                .asSequence()
                .map { it to PromptSimilarity.compatibility(objective, it) }
                .filter { (_, score) -> score >= MIN_COMPATIBILITY }
                .maxWithOrNull(
                    compareBy<Pair<PromptTemplate, Double>> { it.second }
                        .thenBy { it.first.taxaSucessoEfetiva() }
                        .thenBy { it.first.amostrasObservadas }
                )
                ?.first
        }

        if (candidato != null) {
            val score = PromptSimilarity.compatibility(objective, candidato)
            val adapted = adaptPrompt(candidato, objective)
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
            outcomeTracker.markUsed(request.actionId, candidato.id)
            return ActionExecution(
                success = true,
                result = "Encontrei um prompt compatível na biblioteca (compatibilidade ${(score * 100).toInt()}%) e adaptei ao seu pedido:\n\n$adapted",
                evidence = listOf(
                    "prompt-library:${candidato.id}",
                    "prompt-library:observed-samples:${candidato.amostrasObservadas}",
                    "prompt-library:result:pending-real-outcome",
                    "retrieval:biblioteca-local",
                    "compatibility:${"%.2f".format(Locale.US, score)}",
                    "compatibility-source:metadata",
                    "prompt-library:selection-latency-ms:$elapsedMs"
                ),
                provenance = provenance(capability)
            )
        }

        return try {
            val specialistPrompt = buildSpecialistRequest(objective)
            val response = gateway.complete(specialistPrompt, PapelPipeline.ESCRITA_DE_PROMPT)
            val generated = response.text.trim()
            if (generated.isBlank()) return ActionExecution(false, error = "A API especialista não devolveu um prompt válido.", provenance = provenance(capability))

            val savedId = saveGeneratedPrompt(objective, generated)
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
            outcomeTracker.markUsed(request.actionId, savedId)
            ActionExecution(
                success = true,
                result = "Não encontrei prompt compatível na biblioteca. Criei um novo com a API especialista, salvei na biblioteca e deixei pronto para aprendizado por resultado:\n\n$generated",
                evidence = listOf(
                    "retrieval:biblioteca-local:nao-encontrado-ou-abaixo-de-50",
                    "provider:${response.providerId}",
                    "model:${response.modelId}",
                    "prompt-library:saved-before-response",
                    "prompt-library:id:$savedId",
                    "prompt-library:result:pending-real-outcome",
                    "prompt-library:generation-latency-ms:$elapsedMs"
                ),
                provenance = provenance(capability)
            )
        } catch (error: IllegalStateException) {
            ActionExecution(false, error = "A biblioteca não possui prompt compatível (mínimo 50%) e não há API especialista com chave válida registrada. Configure uma API em Configurações → Provedores para criar o prompt.", provenance = provenance(capability))
        } catch (error: Exception) {
            ActionExecution(false, error = "Falha ao gerar prompt: ${error.message ?: error.javaClass.simpleName}", provenance = provenance(capability))
        }
    }

    private fun buildSpecialistRequest(objective: String): String = """
        Você é o especialista de engenharia de prompts do BrainCode.
        Crie um único prompt final, preciso e reutilizável para a tarefa abaixo.
        O pedido pode ser de imagem, código, arquitetura, banco de dados, testes,
        documentação, pesquisa, automação ou outro domínio.
        Preserve a intenção do usuário. Inclua apenas contexto, restrições,
        critérios de saída e detalhes técnicos que sejam pertinentes ao domínio.
        Não invente requisitos ausentes. Responda somente com o prompt final.

        Pedido do usuário:
        $objective
    """.trimIndent()

    private fun adaptPrompt(template: PromptTemplate, objective: String): String {
        var adapted = template.textoTemplate
        Regex("\\{\\{?([A-Za-zÀ-ÿ0-9_]+)\\}?\\}").findAll(template.textoTemplate)
            .map { it.groupValues[1] }
            .distinct()
            .forEach { name ->
                val value = when (name.uppercase(Locale.ROOT)) {
                    "PEDIDO", "OBJETIVO", "TAREFA", "DESCRICAO", "DESCRIÇÃO" -> objective
                    else -> "[${name}: definir conforme o contexto da tarefa]"
                }
                adapted = adapted.replace(Regex("\\{\\{?$name\\}?\\}", RegexOption.IGNORE_CASE), value)
            }
        if (PromptSimilarity.tokenize(adapted).intersect(PromptSimilarity.tokenize(objective)).isEmpty()) adapted += "\n\nContexto específico do pedido: $objective"
        return adapted.trim()
    }

    private fun saveGeneratedPrompt(objective: String, generated: String): String {
        val existing = runBlocking {
            promptLibrary.buscarPorContexto(objective)
                .firstOrNull { PromptSimilarity.contentSimilarity(it.textoTemplate, generated) >= DUPLICATE_THRESHOLD }
        }
        val id = existing?.id ?: "generated-${stableId(objective)}"
        runBlocking {
            promptLibrary.salvarNovaVersao(
                PromptTemplate(
                    id = id,
                    versao = (existing?.versao ?: 0) + 1,
                    finalidade = "geração e melhoria de prompt",
                    contextoDeUso = objective,
                    skillRelacionada = "prompt-generation",
                    agenteRelacionado = "prompt-specialist",
                    textoTemplate = generated,
                    taxaSucesso = existing?.taxaSucesso ?: 0.5,
                    custoMedio = existing?.custoMedio ?: 0.0,
                    tempoMedioMs = existing?.tempoMedioMs ?: 0L,
                    historicoMelhorias = (existing?.historicoMelhorias.orEmpty() + "gerado por API e salvo antes da resposta ao usuário").distinct(),
                    amostrasObservadas = existing?.amostrasObservadas ?: 0
                )
            )
        }
        return id
    }

    private fun stableId(objective: String): String = Integer.toUnsignedString(objective.lowercase(Locale.ROOT).hashCode(), 36)
    private fun PromptTemplate.taxaSucessoEfetiva(): Double = if (amostrasObservadas > 0) taxaSucesso else 0.5
    private fun provenance(capability: CapabilityDefinition) = listOf("app:PromptGenerationExecutor", "capability:${capability.id}")

    private companion object {
        const val MIN_COMPATIBILITY = 0.50
        const val DUPLICATE_THRESHOLD = 0.90
    }
}
