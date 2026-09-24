package com.brain.secretary

import com.brain.conversation.IntentAdvisor
import com.brain.conversation.NoOpIntentAdvisor
import com.brain.prompt.PromptDomain
import com.brain.text.IntentNegation
import com.brain.text.TriggerLexicon

/** Secretário sem LLM: classifica intenção, fase e restrições de forma reproduzível. */
class DeterministicSecretary(
    private val intentAdvisor: IntentAdvisor = NoOpIntentAdvisor
) {
    fun classify(prompt: String): OrderIntent {
        val original = prompt.trim()
        require(original.isNotBlank()) { "prompt não pode ser vazio" }
        val normalized = original.lowercase()
        val restrictions = restrictions(normalized)
        val deterministicDoor = when {
            isPrompt(normalized) -> Door.PROMPT
            isCreation(normalized) -> Door.CREATE
            else -> Door.CHAT
        }
        val precisaRevisaoLLM = hasAmbiguousCreationSignal(normalized)
        val door = if (precisaRevisaoLLM) runCatching {
            intentAdvisor.revisarClassificacao(
                original,
                OrderIntent(
                    originalPrompt = original,
                    door = deterministicDoor,
                    phase = phaseFor(deterministicDoor, normalized),
                    restrictions = restrictions,
                    explicit = isPrompt(normalized) || isCreation(normalized),
                    precisaRevisaoLLM = true
                )
            ).door
        }.getOrDefault(deterministicDoor) else deterministicDoor
        val phase = phaseFor(door, normalized)
        // Contas externas ficam visíveis ao executor desde a classificação (ver
        // docs/LEGADO_E_DECISOES.md, "Escalonamento da Porta 2 e DoorPolicy", item 1); quem decide se de fato
        // usa é cada executor — ex.: PromptGenerationExecutor.escalonar só chama a IA quando o score
        // local é insuficiente ou há gatilho explícito de melhoria ("melhore", "refaça").
        val scope = DoorScope(
            door = door,
            phase = phase,
            restrictions = restrictions,
            externalAccountsAllowed = DoorPolicy.externalAccountsAllowed(door)
        )
        return OrderIntent(
            original, door, phase, restrictions, scope,
            explicit = (door != Door.CHAT) && (isPrompt(normalized) || isCreation(normalized)),
            precisaRevisaoLLM = precisaRevisaoLLM
        )
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
            IntentNegation.hasAllowedOccurrence(text, "transforme", "transformar", "transformando", "alteração", "alteracao", "altere", "modifique", "modificar", "edite", "editar", "converta", "converter", "recrie", "recriar", "aplique", "aplicar")
        return IntentNegation.hasAllowedOccurrence(text, "prompt", "template de prompt", "melhore este prompt", "otimize este prompt") || visualTransformation
    }

    private fun isCreation(text: String): Boolean {
        if (isInformationalCreationQuestion(text)) return false
        if (TriggerLexicon.matches(text, TriggerLexicon.CONTEXTO_SO_CONVERSA)) return false
        if (TriggerLexicon.VETOS_EXPLICITOS_REGEX.any { Regex(it).containsMatchIn(text) }) return false
        return IntentNegation.hasAllowedOccurrence(text, TriggerLexicon.VERBOS_CRIACAO) &&
            (IntentNegation.hasAllowedOccurrence(text, TriggerLexicon.SUBSTANTIVOS_ENTREGAVEL) ||
                IntentNegation.hasAllowedOccurrence(text, "criar projeto", "abrir um projeto", "subir o projeto"))
    }

    private fun hasAmbiguousCreationSignal(text: String): Boolean =
        IntentNegation.hasAllowedOccurrence(text, TriggerLexicon.VERBOS_CONVERSACIONAIS) &&
            IntentNegation.hasAllowedOccurrence(text, TriggerLexicon.SUBSTANTIVOS_ENTREGAVEL) &&
            !TriggerLexicon.VETOS_EXPLICITOS_REGEX.any { Regex(it).containsMatchIn(text) }

    private fun phaseFor(door: Door, text: String): CreatePhase = when (door) {
        Door.CHAT -> CreatePhase.CHAT
        Door.PROMPT -> CreatePhase.PROMPT
        Door.CREATE -> if (isApprovalSignal(text)) CreatePhase.APPROVED else CreatePhase.DISCUSSION
    }

    /** Perguntas sobre como criar explicam uma solução; não autorizam criação. */
    private fun isInformationalCreationQuestion(text: String): Boolean {
        val interrogative = text.contains("?") || text.startsWith("qual ") || text.startsWith("quais ") ||
            text.startsWith("como ") || text.startsWith("o que ")
        val explanatory = text.contains("explic") || text.contains("como funciona") ||
            text.contains("como fazer") || text.contains("qual a melhor") ||
            text.contains("qual o melhor") || text.contains("quais tecnologias") ||
            text.contains("que tecnologias")
        return interrogative && explanatory
    }

    private fun isApprovalSignal(text: String): Boolean = IntentNegation.hasAllowedOccurrence(
        text,
        TriggerLexicon.SINAIS_APROVACAO
    )
}
