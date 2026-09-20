package com.brain.secretary

/** Porta de entrada funcional do BrainCode. */
enum class Door { CHAT, PROMPT, CREATE }

/** Fase do ciclo de uma ordem; CHAT e PROMPT são fluxos terminais especializados. */
enum class CreatePhase {
    CHAT,
    PROMPT,
    DISCUSSION,
    REQUIREMENTS,
    ARCHITECTURE,
    PLAN,
    APPROVED,
    EXECUTION,
    INTEGRATION,
    REVIEW,
    TESTS,
    DELIVERY
}

enum class Restriction {
    NO_WEB,
    NO_PRODUCE,
    NO_EXECUTE,
    NO_EXTERNAL_APIS
}

data class DoorScope(
    val door: Door,
    val phase: CreatePhase,
    val restrictions: Set<Restriction> = emptySet(),
    val externalAccountsAllowed: Boolean = false
) {
    init {
        require(phaseBelongsToDoor()) { "fase $phase não pertence à porta $door" }
        require(!externalAccountsAllowed || Restriction.NO_EXTERNAL_APIS !in restrictions) {
            "uma porta com NO_EXTERNAL_APIS não pode liberar contas externas"
        }
    }

    private fun phaseBelongsToDoor(): Boolean = when (door) {
        Door.CHAT -> phase == CreatePhase.CHAT
        Door.PROMPT -> phase == CreatePhase.PROMPT
        Door.CREATE -> phase !in setOf(CreatePhase.CHAT, CreatePhase.PROMPT)
    }
}

data class OrderIntent(
    val originalPrompt: String,
    val door: Door,
    val phase: CreatePhase,
    val restrictions: Set<Restriction> = emptySet(),
    val scope: DoorScope = DoorScope(door, phase, restrictions),
    val explicit: Boolean = false
) {
    init {
        require(originalPrompt.isNotBlank()) { "ordem original não pode ser vazia" }
        require(scope.door == door) { "scope.door precisa corresponder à porta da intenção" }
        require(scope.phase == phase) { "scope.phase precisa corresponder à fase da intenção" }
        require(scope.restrictions == restrictions) { "scope.restrictions precisa corresponder às restrições da intenção" }
    }
}

data class SecretaryState(
    val currentIntent: OrderIntent? = null,
    val history: List<OrderIntent> = emptyList()
) {
    fun designate(intent: OrderIntent): SecretaryState = copy(
        currentIntent = intent,
        history = (history + intent).takeLast(MAX_HISTORY)
    )

    companion object {
        private const val MAX_HISTORY = 32
    }
}
