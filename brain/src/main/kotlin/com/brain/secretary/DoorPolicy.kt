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

    fun externalAccountsAllowed(door: Door): Boolean = when (door) {
        Door.CHAT, Door.PROMPT, Door.CREATE -> false
    }

    private fun isWeb(capability: String): Boolean = capability == "network.research" || capability.startsWith("network.")
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
