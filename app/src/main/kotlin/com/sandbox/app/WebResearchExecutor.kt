package com.sandbox.app

import com.brain.capability.CapabilityDefinition
import com.brain.gateway.ActionExecution
import com.brain.gateway.ActionExecutor
import com.brain.gateway.ActionRequest
import com.brain.policy.PolicyDecision
import com.brain.research.ResearchResult
import com.brain.research.WebResearchProvider
import java.time.format.DateTimeFormatter

/**
 * Implementação de produção da capability `network.research`. Marcador
 * MUITO IMPORTANTE de design: esta capability nunca retorna `success = false`
 * apenas por falta de internet ou por 0 resultados — pesquisa indisponível
 * é uma condição operacional, não um erro da capacidade em si, e não pode
 * abortar um ciclo cujo passo seguinte (ex.: Prompt Creator) tem fallback
 * local. A indisponibilidade é comunicada no texto do resultado.
 */
class WebResearchExecutor(
    private val provider: WebResearchProvider,
    private val maxResultados: Int = 3
) : ActionExecutor {
    override fun execute(request: ActionRequest, capability: CapabilityDefinition, decision: PolicyDecision): ActionExecution {
        val query = request.parameters["parameter.0"]?.trim().orEmpty()
        if (query.isBlank()) {
            return ActionExecution(
                success = true,
                result = "WebResearch indisponível: nenhuma consulta foi informada. Prosseguindo com conhecimento local.",
                evidence = listOf("web-research:sem-consulta"),
                provenance = provenance(capability)
            )
        }

        val outcome = provider.pesquisar(query, maxResultados)
        val resultados = outcome.getOrNull()
        if (outcome.isFailure || resultados.isNullOrEmpty()) {
            val motivo = outcome.exceptionOrNull()?.message ?: "nenhuma fonte relevante encontrada"
            return ActionExecution(
                success = true,
                result = "WebResearch indisponível ($motivo). Prosseguindo com conhecimento local — a resposta não usará fontes externas.",
                evidence = listOf("web-research:indisponivel:${motivo.take(160)}"),
                provenance = provenance(capability)
            )
        }

        return ActionExecution(
            success = true,
            result = montarResumo(resultados),
            evidence = resultados.map { evidenciaDe(it) },
            provenance = provenance(capability),
            researchSources = resultados
        )
    }

    private fun montarResumo(resultados: List<ResearchResult>): String = buildString {
        append("Pesquisa web concluída com ${resultados.size} fonte(s). ")
        append("As fontes foram preservadas como evidência interna e exibidas separadamente na UI: ")
        append(resultados.joinToString("; ") { "${it.title} (${it.source})" })
    }.trim()

    private fun evidenciaDe(r: ResearchResult): String =
        "web-research:source=${r.source};title=${r.title.take(80)};url=${r.url};retrievedAt=${DateTimeFormatter.ISO_INSTANT.format(r.retrievedAt)}"

    private fun provenance(capability: CapabilityDefinition) = listOf("app:WebResearchExecutor", "capability:${capability.id}")
}
