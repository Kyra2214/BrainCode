package com.sandbox.agent

/**
 * Traduz uma capacidade autorizada em um comando do catálogo do Sandbox.
 * O catálogo é allowlist explícita; não existe capacidade genérica para
 * executar shell/comando arbitrário.
 */
class CapabilityResolver(
    private val catalog: Map<String, CapabilityHandler> = defaultCatalog()
) {
    sealed interface Resolution {
        data class Comando(val argv: List<String>) : Resolution
        data class Refused(val reason: String) : Resolution
    }

    /** Único ponto de tradução capacidade -> comando. Nunca aceita comando pronto do chamador. */
    fun resolve(capacidade: String, parametros: List<String> = emptyList()): Resolution {
        val handler = catalog[capacidade]
            ?: return Resolution.Refused("capacidade fora do catálogo do CapabilityResolver: $capacidade")
        return handler(parametros)
    }

    companion object {
        fun defaultCatalog(): Map<String, CapabilityHandler> = mapOf(
            "sandbox.build" to passthrough("sandbox-build"),
            "sandbox.test" to passthrough("sandbox-test"),
            "sandbox.health" to noArgs("sandbox-health"),
            "sandbox.info" to noArgs("sandbox-info"),
            "sandbox.diagnose" to noArgs("sandbox-diagnose"),
            "sandbox.clean" to noArgs("sandbox-clean")
        )

        private fun noArgs(script: String): CapabilityHandler = { params ->
            if (params.isNotEmpty()) {
                Resolution.Refused("capacidade para '$script' não aceita parâmetros")
            } else {
                Resolution.Comando(listOf(script))
            }
        }

        /**
         * Somente scripts allowlisted recebem parâmetros; os parâmetros são
         * argv, não texto de shell. O script continua responsável pela sua
         * própria validação interna.
         */
        private fun passthrough(script: String): CapabilityHandler = { params ->
            Resolution.Comando(listOf(script) + params)
        }
    }
}

typealias CapabilityHandler = (List<String>) -> CapabilityResolver.Resolution
