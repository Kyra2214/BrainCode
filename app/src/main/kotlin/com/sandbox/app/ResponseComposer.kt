package com.sandbox.app

import com.brain.text.TriggerLexicon
import java.time.Clock
import java.time.format.DateTimeFormatter
import java.util.Locale

data class ComposedChatResponse(
    val text: String,
    val evidence: List<String>
)

/** Compõe texto conversacional a partir de dados já autorizados; não executa efeitos. */
class ResponseComposer(
    private val clock: Clock = Clock.systemDefaultZone(),
    private val conversationEngine: NoInferenceConversationEngine? = null
) {
    fun compose(
        prompt: String,
        research: String = "",
        clarification: Boolean = false,
        context: ConversationContext = ConversationContext()
    ): ComposedChatResponse {
        val lower = prompt.lowercase(Locale.ROOT)
        val evidence = mutableListOf("chat:local-only", "chat:read-only")
        val engineResponse = conversationEngine?.respond(prompt, context)
        val text = when {
            clarification -> {
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
            engineResponse != null -> {
                evidence += engineResponse.evidence
                if (engineResponse.topic != null) evidence += "chat:topic:${engineResponse.topic}"
                engineResponse.text
            }
            contextHasContent(context) -> {
                evidence += "chat:context:read-only"
                formatContext(context)
            }
            conversationEngine != null -> {
                evidence += "chat:conversation-neutral-fallback"
                "Posso conversar sobre isso, mas não reconheci uma resposta local confiável. Pode reformular a pergunta?"
            }
            else -> {
                if (looksLikeFactualQuestion(lower)) {
                    evidence += "chat:factual-question-no-research"
                    "Isso parece pedir um dado atual (ex.: clima, cotação, hora real). Eu não tenho essa informação sem pesquisar — quer que eu pesquise agora?"
                } else {
                    evidence += "chat:conversation"
                    "Entendi o pedido: $prompt\nPosso ajudar a organizar a ideia, os requisitos, as decisões e as pendências sem criar ou executar nada."
                }
            }
        }
        return ComposedChatResponse(text, evidence)
    }

    private fun asksTime(prompt: String): Boolean = TriggerLexicon.matches(prompt, TriggerLexicon.PERGUNTAS_HORA)
    private fun asksDate(prompt: String): Boolean = TriggerLexicon.matches(prompt, TriggerLexicon.PERGUNTAS_DATA)
    private fun looksLikeFactualQuestion(prompt: String): Boolean =
        (TriggerLexicon.matches(prompt, TriggerLexicon.INTERROGATIVOS) &&
            TriggerLexicon.matches(prompt, TriggerLexicon.TEMAS_TEMPO_REAL)) ||
            TriggerLexicon.matches(prompt, TriggerLexicon.CONSULTAS_TEMPO_REAL_SEM_INTERROGATIVO)
    private fun contextHasContent(context: ConversationContext): Boolean =
        context.idea != null || context.requirements.isNotEmpty() || context.decisions.isNotEmpty() || context.pending.isNotEmpty()

    private fun formatContext(context: ConversationContext): String = buildString {
        append("Contexto atual (somente leitura):")
        context.idea?.let { append("\nIdeia: ").append(it) }
        if (context.requirements.isNotEmpty()) append("\nRequisitos: ").append(context.requirements.joinToString("; "))
        if (context.decisions.isNotEmpty()) append("\nDecisões: ").append(context.decisions.joinToString("; "))
        if (context.pending.isNotEmpty()) append("\nPendências: ").append(context.pending.joinToString("; "))
    }
}
