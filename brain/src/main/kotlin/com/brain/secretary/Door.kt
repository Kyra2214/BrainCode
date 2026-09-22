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

    /**
     * Contas externas que esta porta pode enxergar. Sem liberação explícita da porta, nenhuma:
     * o app autoriza todos os providers do catálogo globalmente, mas a Policy nega qualquer passo
     * de uma porta que receba contas que ela não pode usar. Quem monta o PolicyContext deve
     * passar por aqui em vez de repassar a lista global.
     */
    fun visibleAccounts(authorizedAccountIds: Set<String>): Set<String> =
        if (externalAccountsAllowed) authorizedAccountIds else emptySet()

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
    val explicit: Boolean = false,
    /** Verdadeiro quando sinais conversacionais e de criação colidem. */
    val precisaRevisaoLLM: Boolean = false
) {
    init {
        require(originalPrompt.isNotBlank()) { "ordem original não pode ser vazia" }
        require(scope.door == door) { "scope.door precisa corresponder à porta da intenção" }
        require(scope.phase == phase) { "scope.phase precisa corresponder à fase da intenção" }
        require(scope.restrictions == restrictions) { "scope.restrictions precisa corresponder às restrições da intenção" }
    }
}

/** Máquina determinística da Porta 3; não permite saltos nem execução antes de APPROVED. */
object CreatePhaseMachine {
    private val ordered = listOf(
        CreatePhase.DISCUSSION, CreatePhase.REQUIREMENTS, CreatePhase.ARCHITECTURE,
        CreatePhase.PLAN, CreatePhase.APPROVED, CreatePhase.EXECUTION,
        CreatePhase.INTEGRATION, CreatePhase.REVIEW, CreatePhase.TESTS, CreatePhase.DELIVERY
    )

    fun next(current: CreatePhase): CreatePhase? = ordered.getOrNull(ordered.indexOf(current) + 1)

    fun canTransition(current: CreatePhase, target: CreatePhase, explicitApproval: Boolean = false): Boolean {
        val currentIndex = ordered.indexOf(current)
        val targetIndex = ordered.indexOf(target)
        if (currentIndex < 0 || targetIndex != currentIndex + 1) return false
        return target != CreatePhase.APPROVED || explicitApproval
    }

    fun transition(intent: OrderIntent, target: CreatePhase, explicitApproval: Boolean = false): OrderIntent {
        require(intent.door == Door.CREATE) { "somente a Porta 3 possui máquina de fases" }
        require(canTransition(intent.phase, target, explicitApproval)) { "transição inválida: ${intent.phase} -> $target" }
        return intent.copy(phase = target, scope = intent.scope.copy(phase = target))
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

    fun transitionTo(target: CreatePhase, explicitApproval: Boolean = false): SecretaryState {
        val current = requireNotNull(currentIntent) { "não há intenção ativa para transicionar" }
        return designate(CreatePhaseMachine.transition(current, target, explicitApproval))
    }

    fun approve(): SecretaryState {
        var state = this
        val current = requireNotNull(currentIntent) { "não há intenção ativa para aprovar" }.phase
        if (current == CreatePhase.APPROVED || current >= CreatePhase.EXECUTION) return this
        while (state.currentIntent?.phase != CreatePhase.PLAN) {
            state = state.transitionTo(requireNotNull(CreatePhaseMachine.next(state.currentIntent!!.phase)))
        }
        return state.transitionTo(CreatePhase.APPROVED, explicitApproval = true)
    }

    companion object {
        private const val MAX_HISTORY = 32
    }
}
