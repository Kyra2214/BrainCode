package com.sandbox.app

import com.brain.capability.CapabilityDefinition
import com.brain.gateway.ActionExecution
import com.brain.gateway.ActionExecutor
import com.brain.gateway.ActionRequest
import com.brain.policy.PolicyDecision
import com.brain.prompt.PromptLibrary
import com.brain.prompt.PromptTemplate
import com.brain.router.PapelPipeline
import kotlinx.coroutines.runBlocking
import java.util.Locale

/**
 * Fluxo operacional de criação/melhoria de prompts:
 * 1) procura primeiro na biblioteca local;
 * 2) avalia compatibilidade usando os metadados do template;
 * 3) só considera reutilizável um candidato com compatibilidade >= 50%;
 * 4) adapta o template ao pedido atual antes de devolvê-lo;
 * 5) se não houver candidato compatível, usa a API especialista;
 * 6) toda saída de IA é salva na biblioteca ANTES de ser devolvida;
 * 7) prompts novos começam com taxa neutra e aprendem com resultados reais;
 * 8) versões praticamente duplicadas são consolidadas pela biblioteca.
 */
class PromptGenerationExecutor(
    private val gateway: BrainApiGateway,
    private val promptLibrary: PromptLibrary
) : ActionExecutor {

    override fun execute(
        request: ActionRequest,
        capability: CapabilityDefinition,
        decision: PolicyDecision
    ): ActionExecution {
        val objective = request.parameters["parameter.0"]?.trim().orEmpty()
        if (objective.isBlank()) {
            return ActionExecution(false, error = "objetivo do prompt ausente", provenance = provenance(capability))
        }

        val candidato = runBlocking { promptLibrary.buscarPorContexto(objective).firstOrNull() }
        if (candidato != null) {
            val compatibilidade = compatibility(objective, candidato)
            if (compatibilidade >= MIN_COMPATIBILITY) {
                val adapted = adaptPrompt(candidato, objective)
                val confiancaPct = (compatibilidade * 100).toInt()
                return ActionExecution(
                    success = true,
                    result = "Encontrei um prompt compatível na biblioteca (compatibilidade $confiancaPct%) e adaptei ao seu pedido:\n\n$adapted",
                    evidence = listOf(
                        "prompt-library:${candidato.id}",
                        "retrieval:biblioteca-local",
                        "compatibility:${"%.2f".format(Locale.US, compatibilidade)}",
                        "compatibility-source:metadata"
                    ),
                    provenance = provenance(capability)
                )
            }
        }

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
                return ActionExecution(false, error = "A API especialista não devolveu um prompt válido.", provenance = provenance(capability))
            }

            val savedId = saveGeneratedPrompt(objective, generated)
            ActionExecution(
                success = true,
                result = "Não encontrei prompt compatível na biblioteca. Criei um novo com a API especialista, salvei na biblioteca e deixei pronto para aprendizado por resultado:\n\n$generated",
                evidence = listOf(
                    "retrieval:biblioteca-local:nao-encontrado-ou-abaixo-de-50",
                    "provider:${response.providerId}",
                    "model:${response.modelId}",
                    "prompt-library:saved-before-response",
                    "prompt-library:id:$savedId",
                    "prompt-library:initial-success-neutral"
                ),
                provenance = provenance(capability)
            )
        } catch (error: IllegalStateException) {
            ActionExecution(false, error = "A biblioteca não possui prompt compatível (mínimo 50%) e não há API especialista com chave válida registrada. Configure uma API em Configurações → Provedores para criar o prompt.", provenance = provenance(capability))
        } catch (error: Exception) {
            ActionExecution(false, error = "Falha ao gerar prompt: ${error.message ?: error.javaClass.simpleName}", provenance = provenance(capability))
        }
    }

    private fun compatibility(objective: String, template: PromptTemplate): Double {
        val requested = tokenize(objective)
        if (requested.isEmpty()) return 0.0
        val metadata = tokenize("${template.finalidade} ${template.contextoDeUso} ${template.skillRelacionada.orEmpty()}")
        if (metadata.isEmpty()) return 0.0
        val overlap = requested.count { token -> metadata.any { meta -> meta == token || meta.contains(token) || token.contains(meta) } }
        val requestCoverage = overlap.toDouble() / requested.size.toDouble()
        val metadataCoverage = overlap.toDouble() / metadata.size.toDouble().coerceAtLeast(1.0)
        return (requestCoverage * 0.75 + metadataCoverage * 0.25).coerceIn(0.0, 1.0)
    }

    private fun adaptPrompt(template: PromptTemplate, objective: String): String {
        var adapted = template.textoTemplate
        val replacements = linkedMapOf(
            "{pedido}" to objective, "{objetivo}" to objective, "{descricao}" to objective,
            "{tema}" to objective, "{assunto}" to objective, "{cenario}" to objective,
            "{cenário}" to objective, "{personagem}" to objective,
            "{{pedido}}" to objective, "{{objetivo}}" to objective, "{{descricao}}" to objective,
            "{{tema}}" to objective, "{{assunto}}" to objective, "{{cenario}}" to objective,
            "{{cenário}}" to objective, "{{personagem}}" to objective
        )
        replacements.forEach { (placeholder, value) -> adapted = adapted.replace(placeholder, value, ignoreCase = true) }
        if (tokenize(adapted).intersect(tokenize(objective)).isEmpty()) adapted = "$adapted\n\nContexto específico do pedido: $objective"
        return adapted.trim()
    }

    private fun saveGeneratedPrompt(objective: String, generated: String): String {
        val existing = runBlocking { promptLibrary.buscarPorContexto(objective).firstOrNull { similarity(it.textoTemplate, generated) >= DUPLICATE_THRESHOLD } }
        val id = existing?.id ?: "generated-${stableId(objective)}"
        runBlocking {
            promptLibrary.salvarNovaVersao(
                PromptTemplate(
                    id = id,
                    versao = (existing?.versao ?: 0) + 1,
                    finalidade = "geração e melhoria de prompt de imagem",
                    contextoDeUso = objective,
                    skillRelacionada = "prompt-generation",
                    agenteRelacionado = "prompt-specialist",
                    textoTemplate = generated,
                    taxaSucesso = existing?.taxaSucesso ?: 0.5,
                    custoMedio = existing?.custoMedio ?: 0.0,
                    tempoMedioMs = existing?.tempoMedioMs ?: 0L,
                    historicoMelhorias = (existing?.historicoMelhorias.orEmpty() + "gerado por API e salvo antes da resposta ao usuário").distinct()
                )
            )
        }
        return id
    }

    private fun similarity(left: String, right: String): Double {
        val a = tokenize(left); val b = tokenize(right)
        if (a.isEmpty() || b.isEmpty()) return 0.0
        return a.intersect(b).size.toDouble() / a.union(b).size.toDouble()
    }

    private fun stableId(objective: String): String = Integer.toUnsignedString(objective.lowercase(Locale.ROOT).hashCode(), 36)

    private fun tokenize(text: String): Set<String> = text.lowercase(Locale.ROOT)
        .split(Regex("[^\\p{L}\\p{N}]+"))
        .filter { it.length > 2 }
        .toSet()

    private fun provenance(capability: CapabilityDefinition) = listOf("app:PromptGenerationExecutor", "capability:${capability.id}")

    private companion object {
        const val MIN_COMPATIBILITY = 0.50
        const val DUPLICATE_THRESHOLD = 0.90
    }
}
