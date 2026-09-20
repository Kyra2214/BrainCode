package com.brain.secretary

import com.brain.prompt.PromptDomain
import com.brain.text.IntentNegation

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
        if (IntentNegation.hasNegatedOccurrence(text, WEB_TERMS)) add(Restriction.NO_WEB)
        if (IntentNegation.hasNegatedOccurrence(text, PRODUCTION_TERMS)) add(Restriction.NO_PRODUCE)
        if (IntentNegation.hasNegatedOccurrence(text, EXECUTION_TERMS)) add(Restriction.NO_EXECUTE)
    }

    private fun isPrompt(text: String): Boolean {
        if (text.startsWith("/")) return true
        val visualTransformation = PromptDomain.classificar(text) == PromptDomain.IMAGEM &&
            IntentNegation.hasAllowedOccurrence(text, "transform", "alter", "modific", "edita", "conver", "recri", "aplic")
        return IntentNegation.hasAllowedOccurrence(text, "prompt", "template de prompt", "melhore este prompt", "otimize este prompt") || visualTransformation
    }

    private fun isCreation(text: String): Boolean {
        if (SOFT_CREATION_CONTEXT.any { it in text }) return false
        return IntentNegation.hasAllowedOccurrence(
            text,
            "criar aplicativo", "crie um aplicativo", "criar um app", "desenvolv", "implementar", "implemente", "construir", "construa", "montar um sistema", "criação", "desenvolvimento"
        ) || (IntentNegation.hasAllowedOccurrence(text, "aplicativo", "aplicação", "software", "site", "sistema") &&
            IntentNegation.hasAllowedOccurrence(text, "criar", "crie", "desenvolv", "implementar", "implemente", "constru"))
    }

    private fun isApprovalSignal(text: String): Boolean = IntentNegation.hasAllowedOccurrence(
        text,
            "pode começar", "pode iniciar", "comece o desenvolvimento", "inicie o desenvolvimento", "pode desenvolver", "pode implementar", "implemente", "execute o plano", "execute os testes", "pode executar"
    )

    private companion object {
        val WEB_TERMS = listOf("pesquis", "internet", "web", "fontes", "referências")
        val PRODUCTION_TERMS = listOf("criar", "crie", "produzir", "escrever", "gerar", "desenvolver", "implementar", "projeto", "aplicativo", "sistema", "artefato")
        val EXECUTION_TERMS = listOf("executar", "execute", "execução", "rodar", "compilar", "testar", "testes")
        val SOFT_CREATION_CONTEXT = listOf("estou pensando em", "vamos discutir", "quero discutir", "apenas planejar", "só planejar", "tenho uma ideia")
    }
}
