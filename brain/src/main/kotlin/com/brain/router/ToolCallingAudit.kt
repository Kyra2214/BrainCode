package com.brain.router

/** Resultado conservador da auditoria de uma capacidade de tool-calling. */
enum class ToolSupportStatus {
    CONFIRMED,
    UNSUPPORTED,
    UNKNOWN
}

enum class ToolArgumentPolicy {
    CLIENT_VALIDATES,
    PROVIDER_VALIDATES,
    UNKNOWN
}

data class ToolCallingAudit(
    val request: ToolSupportStatus = ToolSupportStatus.UNKNOWN,
    val response: ToolSupportStatus = ToolSupportStatus.UNKNOWN,
    val modelVerified: Boolean = false,
    val arguments: ToolArgumentPolicy = ToolArgumentPolicy.UNKNOWN,
    val fallbackInstruction: String = DEFAULT_FALLBACK_INSTRUCTION,
    val sources: List<String> = emptyList()
) {
    /** Só permite enviar tools quando o modelo concreto foi verificado. */
    fun allowsTools(): Boolean =
        modelVerified && request == ToolSupportStatus.CONFIRMED && response == ToolSupportStatus.CONFIRMED

    companion object {
        const val DEFAULT_FALLBACK_INSTRUCTION =
            "Responda somente em texto natural. Não simule chamadas de ferramenta, não invente execução nem alegue ações externas."
    }
}
