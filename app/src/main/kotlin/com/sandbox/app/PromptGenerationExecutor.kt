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
 * 2) avalia compatibilidade usando os metadados do template, não o texto cru com placeholders;
 * 3) só considera reutilizável um candidato com compatibilidade >= 50%;
 * 4) adapta o template ao pedido atual antes de devolvê-lo;
 * 5) se não houver candidato compatível, usa a API especialista em prompt;
 * 6) toda saída de IA é salva na biblioteca ANTES de ser devolvida;
 * 7) sem API/chave válida, retorna erro explícito ao usuário.
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

        // A biblioteca já ranqueia candidatos por contexto/finalidade.
        // Não usamos mais o texto cru do template para decidir os 50%, porque
        // placeholders como {PERSONAGEM}, {LUZ} e {CENARIO} não contêm o
        // vocabulário do pedido e produziam falsos negativos.
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

    /**
     * Compatibilidade lexical contextual: pedido x finalidade/contexto do
     * template. O score é calculado contra metadados catalogados, nunca contra
     * placeholders do template. A interseção é calculada nos dois sentidos e
     * combinada para evitar que um texto muito curto domine por acidente.
     */
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
            "{pedido}" to objective,
            "{objetivo}" to objective,
            "{descricao}" to objective,
            "{tema}" to objective,
            "{assunto}" to objective,
            "{cenario}" to objective,
            "{cenário}" to objective,
            "{personagem}" to objective,
            "{{pedido}}" to objective,
            "{{objetivo}}" to objective,
            "{{descricao}}" to objective,
            "{{tema}}" to objective,
            "{{assunto}}" to objective,
            "{{cenario}}" to objective,
            "{{cenário}}" to objective,
            "{{personagem}}" to objective
        )
        replacements.forEach { (placeholder, value) ->
            adapted = adapted.replace(placeholder, value, ignoreCase = true)
        }
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
        const val MIN_COMPATIBILITY = 0.50
    }
}
