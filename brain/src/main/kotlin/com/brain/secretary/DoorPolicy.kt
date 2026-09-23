package com.brain.secretary

/** Autoridade de escopo da porta; o Planner apenas sugere, esta matriz decide. */
object DoorPolicy {
    fun allows(scope: DoorScope, capability: String): Boolean {
        if (capability.isBlank()) return false
        if (Restriction.NO_WEB in scope.restrictions && isWeb(capability)) return false
        if (Restriction.NO_PRODUCE in scope.restrictions && isProduction(capability)) return false
        if (Restriction.NO_EXECUTE in scope.restrictions && isExecution(capability)) return false
        if (Restriction.NO_EXTERNAL_APIS in scope.restrictions && isExternal(capability)) return false
        if (isExternal(capability) && !scope.externalAccountsAllowed) return false

        return when (scope.door) {
            Door.CHAT -> capability in CHAT_CAPABILITIES
            Door.PROMPT -> capability in PROMPT_CAPABILITIES
            Door.CREATE -> when {
                isWriteOrExecute(capability) -> scope.phase >= CreatePhase.APPROVED
                else -> capability in CREATE_BASE_CAPABILITIES
            }
        }
    }

    /**
     * Contas externas *visíveis* ao executor da porta — não "usadas": cada executor decide se e
     * quando de fato chama a IA (ex.: [com.sandbox.app.PromptGenerationExecutor.escalonar] só
     * escala quando o score local é insuficiente ou há gatilho explícito de melhoria). Chat nunca
     * libera; Prompt e Criação liberam desde o início, no mesmo padrão.
     *
     * Corrigido em 23/09/2026 (ver docs/auditoria/PLANO_CORRECAO_AUDITORIA_ESCALONAMENTO.md, item 1):
     * antes, a Porta 2 só liberava contas quando o texto original já continha um gatilho de
     * melhoria ("melhore", "refaça"), calculado uma única vez em `DeterministicSecretary.classify()`
     * — antes de qualquer prompt existir. Isso tornava o escalonamento por qualidade insuficiente
     * inalcançável num primeiro pedido ("crie um prompt de X"): a IA nunca era chamada nesse
     * caminho, mesmo com `PromptQualityValidator` apontando score abaixo do padrão, porque
     * `authorizedAccountIds` chegava sempre vazio ao executor.
     */
    fun externalAccountsAllowed(door: Door): Boolean = when (door) {
        Door.CHAT -> false
        Door.PROMPT -> true
        Door.CREATE -> true
    }

    private fun isWeb(capability: String): Boolean = capability == "network.research" || capability.startsWith("network.")

    /**
     * Reservado para uso futuro: hoje nenhuma capability registrada no catálogo/planner usa os
     * prefixos "provider."/"account."/"external." (verificado em toda a base em 23/09/2026 — ver
     * docs/auditoria/PLANO_CORRECAO_AUDITORIA_ESCALONAMENTO.md, seção 3). O gating real de chamada de API/conta hoje
     * é feito só por [DoorScope.externalAccountsAllowed] + o filtro de authorizedAccountIds no
     * AccountPool (BrainApiGateway.complete). Se no futuro uma UI/fluxo passar a autorizar acesso a
     * provider/conta via capability nomeada (em vez de só o booleano), esta função passa a valer —
     * até lá, mantê-la aqui é intencional e documentado, não é acaso.
     */
    private fun isExternal(capability: String): Boolean = capability.startsWith("provider.") || capability.startsWith("account.") || capability.startsWith("external.")
    private fun isProduction(capability: String): Boolean = capability == "workspace.write" || capability.startsWith("workspace.") || capability == "prompt.library.write"
    private fun isExecution(capability: String): Boolean = capability == "sandbox.code" || capability.startsWith("sandbox.build") || capability.startsWith("sandbox.test")
    private fun isWriteOrExecute(capability: String): Boolean = isProduction(capability) || isExecution(capability)

    private val CHAT_CAPABILITIES = setOf("brain.analyze", "chat.respond", "sandbox.diagnose", "network.research", "sandbox.info", "sandbox.health")
    private val PROMPT_CAPABILITIES = setOf("brain.analyze", "chat.respond", "sandbox.diagnose", "network.research", "prompt.library.write", "prompt.library.generate", "sandbox.info")
    private val CREATE_BASE_CAPABILITIES = setOf(
        "brain.analyze", "chat.respond", "sandbox.diagnose", "network.research", "sandbox.info", "sandbox.health",
        "brain.requirements", "brain.plan"
    )
}
