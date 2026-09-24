package com.sandbox.app

import org.json.JSONArray
import org.json.JSONObject
import com.brain.text.InformationalQuestionClassifier
import java.util.Locale

/** Resultado estruturado do engine simbólico; não executa capabilities nem efeitos. */
data class ConversationResponse(
    val text: String,
    val intent: String,
    val topic: String? = null,
    val evidence: List<String> = emptyList()
)

/**
 * Adapter Android do núcleo conversacional do TheShovel/no-inference.
 *
 * A estrutura preservada é a mesma do projeto de origem: pattern matcher,
 * templates, knowledge lookup, contexto/follow-up e memória factual. O
 * BrainCode continua dono da memória persistente, policy, router, capabilities,
 * execução, evidência e post-execution gate.
 */
class NoInferenceConversationEngine(
    private val assets: (String) -> String,
    private val memory: MutableMap<String, MutableList<String>> = linkedMapOf()
) {
    private data class PatternEntry(val category: String, val regex: Regex, val responses: List<String>)
    private data class KnowledgeEntry(val questions: List<String>, val answer: String)
    private data class Turn(val user: String, val assistant: String, val topic: String?)

    private val patterns: List<PatternEntry> by lazy { loadPatterns() }
    private val knowledge: List<KnowledgeEntry> by lazy { loadKnowledge() }
    private val turns = ArrayDeque<Turn>()
    private var responseIndex = 0

    fun respond(prompt: String, context: ConversationContext = ConversationContext()): ConversationResponse? {
        val normalized = normalize(prompt)
        if (normalized.isBlank()) return null

        rememberFacts(normalized)
        val followUp = followUp(normalized)
        if (followUp != null) return record(prompt, followUp)

        val social = patterns.firstOrNull { it.regex.containsMatchIn(normalized) }
        if (social != null) {
            val response = social.responses[responseIndex++ % social.responses.size]
            return record(prompt, ConversationResponse(response, "social.${social.category}", evidence = listOf("no-inference:pattern-matcher", "no-inference:template")))
        }

        val memoryAnswer = recall(normalized)
        if (memoryAnswer != null) return record(prompt, ConversationResponse(memoryAnswer, "memory.recall", evidence = listOf("no-inference:fact-memory")))

        val knowledgeMatch = lookupKnowledge(normalized)
        if (knowledgeMatch != null) {
            val topic = extractTopic(normalized)
            return record(prompt, ConversationResponse(knowledgeMatch, "knowledge.lookup", topic, listOf("no-inference:knowledge")))
        }

        val topic = extractTopic(normalized)
        if (topic != null && isInformational(normalized)) {
            val previous = turns.lastOrNull { it.topic != null }?.topic
            val resolved = topic.takeIf { it.length > 2 } ?: previous
            val text = if (resolved != null) {
                "Posso explicar sobre $resolved, mas ainda não tenho uma entrada local suficiente para responder com segurança."
            } else {
                "Posso tentar responder, mas preciso de um pouco mais de contexto sobre o tema."
            }
            return record(prompt, ConversationResponse(text, "knowledge.unknown", resolved, listOf("no-inference:neutral-fallback")))
        }

        return null
    }

    private fun record(prompt: String, response: ConversationResponse): ConversationResponse {
        turns.addLast(Turn(prompt, response.text, response.topic))
        while (turns.size > 12) turns.removeFirst()
        return response
    }

    private fun followUp(query: String): ConversationResponse? {
        val last = turns.lastOrNull() ?: return null
        val topic = last.topic ?: return null
        val explicitTopic = extractTopic(query)?.takeIf { it !in setOf("melhor", "mais", "detalhes", "isso") }
        val asksContinuation = listOf(
            "explique melhor", "pode explicar melhor", "e como funciona", "como isso funciona",
            "fale mais", "continue", "mais detalhes", "não entendi"
        ).any { query.contains(it) } && explicitTopic == null
        if (!asksContinuation) return null
        val entry = knowledge.firstOrNull { item -> item.questions.any { normalize(it).contains(topic) } }
        val text = entry?.answer ?: "Posso continuar explicando $topic a partir desse ponto, mas não tenho mais detalhes locais confiáveis."
        return ConversationResponse(text, "follow-up.context", topic, listOf("no-inference:context-extraction", "no-inference:follow-up"))
    }

    private fun rememberFacts(query: String) {
        val patterns = listOf(
            Regex("(?:eu gosto de|eu amo|adoro) (.+)") to "interest",
            Regex("eu tenho (?:um|uma|um a)?\\s*(.+)") to "possession",
            Regex("eu uso (.+)") to "tool"
        )
        patterns.forEach { (pattern, key) ->
            pattern.find(query)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.length > 2 }?.let { value ->
                memory.getOrPut(key) { mutableListOf() }.apply { if (value !in this) add(value) }
            }
        }
    }

    private fun recall(query: String): String? {
        if (query.contains("do que eu gosto") || query.contains("o que eu gosto")) {
            val values = memory.values.flatten()
            if (values.isNotEmpty()) return "Pelo que você mencionou, você gosta de ${values.joinToString(", ")}."
        }
        if (query.contains("o que eu tenho") && memory["possession"].orEmpty().isNotEmpty()) {
            return "Você mencionou que tem ${memory["possession"]!!.joinToString(", ")}."
        }
        return null
    }

    private fun lookupKnowledge(query: String): String? {
        knowledge.firstOrNull { entry -> entry.questions.any { normalize(it) == query } }?.let { return it.answer }
        val normalizedEntries = knowledge.asSequence()
            .map { entry -> entry to entry.questions.maxByOrNull { overlap(normalize(it), query) } }
            .filter { (_, question) -> question != null }
            .map { (entry, question) -> entry to overlap(normalize(question!!), query) }
            .filter { (_, score) -> score >= 0.45 }
            .maxByOrNull { (_, score) -> score }
        return normalizedEntries?.first?.answer
    }

    private fun overlap(a: String, b: String): Double {
        val stop = setOf("o", "a", "os", "as", "um", "uma", "é", "e", "de", "do", "da", "que", "como", "me",
            "explique", "explica", "fale", "falar", "sobre", "descreva", "descrever", "funciona")
        val left = a.split(Regex("[^\\p{L}\\p{N}]+" )).filter { it.length > 2 && it !in stop }.toSet()
        val right = b.split(Regex("[^\\p{L}\\p{N}]+" )).filter { it.length > 2 && it !in stop }.toSet()
        if (left.isEmpty()) return 0.0
        val shared = left.intersect(right)
        if (shared.isEmpty()) return 0.0
        return shared.size.toDouble() / left.size.toDouble()
    }

    private fun isInformational(query: String): Boolean =
        InformationalQuestionClassifier.isRecoverable(query)

    private fun extractTopic(query: String): String? {
        val patterns = listOf(
            Regex("(?:o que é|o que e|o que significa|explique|como funciona|quem inventou|quem criou)\\s+(?:um|uma|o|a)?\\s*(.+?)(?:[?!.]|$)"),
            Regex("(?:sobre|a respeito de)\\s+(.+?)(?:[?!.]|$)")
        )
        return patterns.firstNotNullOfOrNull { it.find(query)?.groupValues?.getOrNull(1)?.trim() }
            ?.trim(' ', '?', '.', '!')
            ?.takeIf { it.length > 2 }
    }

    private fun normalize(text: String): String = text.lowercase(Locale.ROOT).trim().replace(Regex("\\s+"), " ")

    private fun loadPatterns(): List<PatternEntry> {
        val files = listOf("pt_br_social_patterns.json", "social_patterns.json", "extended_social_patterns.json", "slang_patterns.json")
        return files.flatMap { file ->
            val root = JSONObject(assets("no_inference/patterns/$file"))
            root.keys().asSequence().filterNot { it.startsWith("_") }.flatMap { category ->
                val item = root.getJSONObject(category)
                val response = item.get("response")
                val responses = when (response) {
                    is JSONArray -> response.toStringList()
                    else -> listOf(response.toString())
                }
                item.getJSONArray("patterns").toStringList().mapNotNull { pattern ->
                    runCatching { Regex(pattern, RegexOption.IGNORE_CASE) }.getOrNull()
                        ?.let { PatternEntry(category, it, responses) }
                }.asSequence()
            }.toList()
        }
    }

    private fun loadKnowledge(): List<KnowledgeEntry> {
        val json = assets("no_inference/knowledge/pt_br_conversation.json")
        val array = JSONArray(json)
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            KnowledgeEntry(item.getJSONArray("q").toStringList(), item.optString("a").trim()).takeIf { it.answer.isNotBlank() }
        }
    }

    private fun JSONArray.toStringList(): List<String> = (0 until length()).mapNotNull { optString(it).takeIf(String::isNotBlank) }
}

fun noInferenceEngine(context: android.content.Context): NoInferenceConversationEngine =
    NoInferenceConversationEngine(assets = { path -> context.assets.open(path).bufferedReader().use { it.readText() } })

private fun JSONArray.optString(index: Int): String = optString(index, "")
