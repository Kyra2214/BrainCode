package com.sandbox.app

import com.brain.capability.CapabilityDefinition
import com.brain.gateway.ActionExecution
import com.brain.gateway.ActionExecutor
import com.brain.gateway.ActionRequest
import com.brain.policy.PolicyDecision
import java.time.Clock
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Resposta conversacional local da Porta 1. Não chama rede, provider, shell ou
 * escrita de projeto; Web e memória entram como evidência/contexto já autorizado.
 */
class ChatResponseExecutor(
    private val clock: Clock = Clock.systemDefaultZone(),
    private val contextProvider: () -> ConversationContext = { ConversationContext() }
) : ActionExecutor {
    override fun execute(request: ActionRequest, capability: CapabilityDefinition, decision: PolicyDecision): ActionExecution {
        val prompt = request.parameters["parameter.0"]?.trim().orEmpty()
        if (prompt.isBlank()) {
            return ActionExecution(false, error = "mensagem conversacional ausente", provenance = provenance(capability))
        }

        val lower = prompt.lowercase(Locale.ROOT)
        val evidence = mutableListOf<String>("chat:local-only", "chat:read-only")
        val research = request.parameters["parameter.1"]?.trim().orEmpty()
        val isClarification = request.parameters.values.any { it.startsWith("clarification.status=NEEDS_CLARIFICATION") }
        val context = contextProvider()
        val response = when {
            isClarification -> {
                evidence += "chat:clarification-question"
                "Preciso de um esclarecimento antes de continuar: $prompt"
            }
            asksTime(lower) -> {
                evidence += "chat:clock:${clock.instant()}"
                "Agora são ${DateTimeFormatter.ofPattern("HH:mm", Locale("pt", "BR")).withZone(clock.zone).format(clock.instant())}."
            }
            asksDate(lower) -> {
                evidence += "chat:clock:${clock.instant()}"
                "Hoje é ${DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale("pt", "BR")).withZone(clock.zone).format(clock.instant())}."
            }
            research.isNotBlank() -> {
                evidence += "chat:research-context-included"
                "Pedido analisado: $prompt\nResumo baseado nas fontes autorizadas recebidas nesta etapa:\n$research"
            }
            contextHasContent(context) -> {
                evidence += "chat:context:read-only"
                formatContext(context)
            }
            else -> {
                evidence += "chat:conversation"
                "Entendi o pedido: $prompt\nPosso ajudar a organizar a ideia, os requisitos, as decisões e as pendências sem criar ou executar nada."
            }
        }
        val provenance = provenance(capability).toMutableList()
        if (research.isNotBlank()) provenance += "source:dependency:network.research"
        return ActionExecution(true, result = response, evidence = evidence, provenance = provenance)
    }

    private fun asksTime(prompt: String): Boolean = listOf("que horas", "qual a hora", "horário", "horario").any { it in prompt }
    private fun asksDate(prompt: String): Boolean = listOf("que dia", "qual a data", "data de hoje", "hoje é", "hoje e").any { it in prompt }
    private fun contextHasContent(context: ConversationContext): Boolean =
        context.idea != null || context.requirements.isNotEmpty() || context.decisions.isNotEmpty() || context.pending.isNotEmpty()

    private fun formatContext(context: ConversationContext): String = buildString {
        append("Contexto atual (somente leitura):")
        context.idea?.let { append("\nIdeia: ").append(it) }
        if (context.requirements.isNotEmpty()) append("\nRequisitos: ").append(context.requirements.joinToString("; "))
        if (context.decisions.isNotEmpty()) append("\nDecisões: ").append(context.decisions.joinToString("; "))
        if (context.pending.isNotEmpty()) append("\nPendências: ").append(context.pending.joinToString("; "))
    }

    private fun provenance(capability: CapabilityDefinition) = listOf("app:ChatResponseExecutor", "capability:${capability.id}")
}
