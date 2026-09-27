package com.brain.conversation

import com.brain.secretary.Door
import com.brain.secretary.OrderIntent

/** Local-first advisory layer; the deterministic Secretary remains authoritative. */
class HybridIntentAdvisor(
    private val local: IntentAdvisor,
    private val cloud: IntentAdvisor = NoOpIntentAdvisor,
    private val maxPromptChars: Int = 1200,
    private val minLocalConfidence: Double = 0.70,
    private val metrics: ConversationMetrics? = null
) : IntentAdvisor {
    init {
        require(maxPromptChars > 0)
        require(minLocalConfidence in 0.0..1.0)
    }

    override fun revisarClassificacao(prompt: String, classificacaoTentativa: OrderIntent): OrderIntentSugerido {
        if (prompt.length > maxPromptChars || containsComplexSignal(prompt)) {
            val result = runCatching { cloud.revisarClassificacao(prompt, classificacaoTentativa) }
                .getOrElse { fallback(classificacaoTentativa) }
            metrics?.recordLlmCall("intent", "cloud", result != null)
            return result
        }
        val localSuggestion = runCatching {
            local.revisarClassificacao(prompt, classificacaoTentativa)
        }.getOrNull()
        if (localSuggestion != null && localSuggestion.door in DOORS &&
            (localSuggestion.confidence ?: 0.0) >= minLocalConfidence
        ) {
            metrics?.recordLlmCall("intent", "local", true)
            return localSuggestion.copy(rationale = "local-advisor")
        }
        val result = runCatching { cloud.revisarClassificacao(prompt, classificacaoTentativa) }
            .getOrElse { fallback(classificacaoTentativa) }
        metrics?.recordLlmCall("intent", "cloud", result != null)
        return result
    }

    private fun fallback(intent: OrderIntent) = OrderIntentSugerido(door = intent.door, confidence = 0.0, rationale = "advisory-fallback")

    private fun containsComplexSignal(prompt: String): Boolean = listOf(
        "```", "http://", "https://", "api", "código", "codigo", "stack trace",
        "workflow", "sandbox", "executa", "implemente", "programa"
    ).any { prompt.contains(it, ignoreCase = true) }

    private companion object {
        val DOORS = setOf(Door.CHAT, Door.PROMPT, Door.CREATE)
    }
}
