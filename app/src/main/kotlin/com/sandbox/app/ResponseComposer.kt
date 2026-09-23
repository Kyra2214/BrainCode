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
        context: ConversationContext = ConversationContext(),
        localLookupCompleted: Boolean = false,
        precomputedConversation: ConversationResponse? = null
    ): ComposedChatResponse {
        val lower = prompt.lowercase(Locale.ROOT)
        val evidence = mutableListOf("chat:local-only", "chat:read-only")
        val engineResponse = if (localLookupCompleted) precomputedConversation else conversationEngine?.respond(prompt, context)
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
                synthesizeResearch(prompt, research)
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
                evidence += "chat:conversation:local-miss"
                "Entendi o pedido: $prompt\nPosso ajudar a organizar a resposta com segurança, preservando o tema solicitado."
            }
            else -> {
                if (looksLikeFactualQuestion(lower)) {
                    evidence += "chat:conversation:local-miss"
                    "Não tenho conhecimento suficiente para responder a essa pergunta com segurança neste momento."
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

    private fun synthesizeResearch(prompt: String, raw: String): String {
        val topicTerms = Regex("[\\p{L}\\p{N}]{4,}").findAll(prompt.lowercase(Locale.ROOT))
            .map { it.value }.filterNot { it in setOf("como", "funciona", "sobre", "explique", "fale", "qual", "quais") }.toSet()
        val cleaned = raw
            .replace(Regex("<[^>]+>"), " ")
            .replace(Regex("https?://\\S+"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
        val sentences = cleaned.split(Regex("(?<=[.!?])\\s+|\\n+"))
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .filter { topicTerms.isEmpty() || topicTerms.any { term -> it.lowercase(Locale.ROOT).contains(term) } }
            .take(4)
        return if (sentences.isEmpty()) "Encontrei fontes, mas não há conteúdo suficientemente relacionado ao tema para responder com segurança."
        else sentences.joinToString(" ").take(1600)
    }
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
