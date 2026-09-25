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
        val localCalculation = LocalArithmeticCalculator.calculate(prompt)
        val text = when {
            clarification -> {
                evidence += "chat:clarification-question"
                "Preciso de um esclarecimento antes de continuar: $prompt"
            }
            localCalculation != null -> {
                evidence += "chat:calculation:local"
                localCalculation
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
            engineResponse != null && engineResponse.intent != "knowledge.unknown" -> {
                evidence += engineResponse.evidence
                if (engineResponse.topic != null) evidence += "chat:topic:${engineResponse.topic}"
                engineResponse.text
            }
            engineResponse?.intent == "knowledge.unknown" -> {
                evidence += "chat:conversation:local-miss"
                "Não tenho conhecimento suficiente para responder a essa pergunta com segurança neste momento."
            }
            contextHasContent(context) -> {
                evidence += "chat:context:read-only"
                formatContext(context)
            }
            conversationEngine != null -> {
                evidence += "chat:conversation:local-miss"
                "Não tenho conhecimento suficiente para responder a essa solicitação com segurança neste momento."
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
            .filter { it.isNotBlank() && !isBoilerplate(it) }
        val related = sentences.filter { sentence ->
            topicTerms.isEmpty() || topicTerms.any { term -> sentence.lowercase(Locale.ROOT).contains(term) }
        }
        val factual = looksLikeFactualQuestion(prompt.lowercase(Locale.ROOT)) ||
            Regex("(?i)\\b(tempo|clima|temperatura|previsão|previsao|cotação|cotacao|preço|preco|data|horário|horario)\\b").containsMatchIn(prompt)
        val concrete = related.filter(::hasConcreteFact)
        val selected = when {
            factual && concrete.isNotEmpty() -> concrete.take(2)
            related.isNotEmpty() -> related.take(if (factual) 2 else 4)
            concrete.isNotEmpty() -> concrete.take(2)
            // Nunca use a primeira sentença como fallback: páginas podem começar
            // com menus, contexto editorial ou conteúdo fora do tema. Sem evidência
            // relacionada, a resposta deve permanecer inconclusiva para o gate tratar
            // a pesquisa como insuficiente, em vez de inventar uma resposta por posição.
            else -> emptyList()
        }
        return if (selected.isEmpty()) "Não encontrei informação suficientemente relacionada ao tema para responder com segurança."
        else selected.joinToString(" ").take(if (factual) 700 else 1600)
    }

    private fun isBoilerplate(sentence: String): Boolean {
        val normalized = sentence.lowercase(Locale.ROOT).replace(Regex("\\s+"), " ").trim()
        if (normalized.length < 3) return true
        val uiPattern = Regex("(?i)\\b(cookie|cookies|privacidade|privacy|termos de uso|terms of use|aceitar|accept|recusar|reject|login|log in|sign in|sign up|inscreva-se|menu|navigation|navegação|idioma|language|home|subscribe|assine|advertise|anuncie|javascript)\\b")
        val navigationLike = normalized.count { it == '|' || it == '›' || it == '·' } >= 2 ||
            (normalized.split(Regex("[,|]")).size >= 5 && normalized.length < 180)
        return uiPattern.containsMatchIn(normalized) || navigationLike
    }

    private fun hasConcreteFact(sentence: String): Boolean =
        Regex("(?i)(\\d+(?:[.,]\\d+)?\\s*(?:°|graus|c|f|km/h|mm|%|mb|gb|anos?|dias?|horas?)?|\\b(?:hoje|amanhã|ontem|segunda|terça|quarta|quinta|sexta|sábado|domingo)\\b|\\b20\\d{2}\\b)")
            .containsMatchIn(sentence)
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
