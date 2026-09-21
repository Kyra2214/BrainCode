package com.brain.secretary

import com.brain.prompt.PromptDomain
import com.brain.text.IntentNegation
import com.brain.text.TriggerLexicon

/** Secretário sem LLM: classifica intenção, fase e restrições de forma reproduzível. */
class DeterministicSecretary {
    fun classify(prompt: String): OrderIntent {
        val original = prompt.trim()
        require(original.isNotBlank()) { "prompt não pode ser vazio" }
        val normalized = original.lowercase()
        val restrictions = restrictions(normalized)
        val door = when {
            isPrompt(normalized) -> Door.PROMPT
            isCreation(normalized) -> Door.CREATE
            else -> Door.CHAT
        }
        val phase = when (door) {
            Door.CHAT -> CreatePhase.CHAT
            Door.PROMPT -> CreatePhase.PROMPT
            Door.CREATE -> if (isApprovalSignal(normalized)) CreatePhase.APPROVED else CreatePhase.DISCUSSION
        }
        val scope = DoorScope(
            door = door,
            phase = phase,
            restrictions = restrictions,
            externalAccountsAllowed = DoorPolicy.externalAccountsAllowed(door)
        )
        return OrderIntent(original, door, phase, restrictions, scope, explicit = isPrompt(normalized) || isCreation(normalized))
    }

    fun isApproval(prompt: String): Boolean = isApprovalSignal(prompt.trim().lowercase())

    private fun restrictions(text: String): Set<Restriction> = buildSet {
        if (IntentNegation.hasNegatedOccurrence(text, TriggerLexicon.WEB_TERMS)) add(Restriction.NO_WEB)
        if (IntentNegation.hasNegatedOccurrence(text, TriggerLexicon.VERBOS_CRIACAO + TriggerLexicon.SUBSTANTIVOS_ENTREGAVEL)) add(Restriction.NO_PRODUCE)
        if (IntentNegation.hasNegatedOccurrence(text, TriggerLexicon.EXECUTION_TERMS)) add(Restriction.NO_EXECUTE)
    }

    private fun isPrompt(text: String): Boolean {
        if (text.startsWith("/")) return true
        val visualTransformation = PromptDomain.classificar(text) == PromptDomain.IMAGEM &&
            IntentNegation.hasAllowedOccurrence(text, "transform", "alter", "modific", "edita", "conver", "recri", "aplic")
        return IntentNegation.hasAllowedOccurrence(text, "prompt", "template de prompt", "melhore este prompt", "otimize este prompt") || visualTransformation
    }

    private fun isCreation(text: String): Boolean {
        if (TriggerLexicon.CONTEXTO_SO_CONVERSA.any { it in text }) return false
        if (TriggerLexicon.VETOS_EXPLICITOS_REGEX.any { Regex(it).containsMatchIn(text) }) return false
        return IntentNegation.hasAllowedOccurrence(text, TriggerLexicon.VERBOS_CRIACAO) &&
            (IntentNegation.hasAllowedOccurrence(text, TriggerLexicon.SUBSTANTIVOS_ENTREGAVEL) ||
                IntentNegation.hasAllowedOccurrence(text, "criar projeto", "abrir um projeto", "subir o projeto"))
    }

    private fun isApprovalSignal(text: String): Boolean = IntentNegation.hasAllowedOccurrence(
        text,
        TriggerLexicon.SINAIS_APROVACAO
    )
}
