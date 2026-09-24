package com.sandbox.sandbox

/**
 * Catálogo mínimo e reproduzível de probes internos. Os probes são apenas
 * descrições: a execução continua delegada a um executor autorizado pelo
 * Sandbox e nunca recebe alvos externos implicitamente.
 */
object SecurityScenarioCatalog {
    val baseline: List<SecurityScenario> = listOf(
        SecurityScenario("secret.redaction", "Credencial sintética é redigida", "secrets", expectedBlocked = true),
        SecurityScenario("path.traversal", "Traversal de caminho é bloqueado", "filesystem", expectedBlocked = true),
        SecurityScenario("command.injection", "Metacaracteres de shell são rejeitados", "execution", expectedBlocked = true),
        SecurityScenario("network.ssrf", "Destino reservado é bloqueado", "network", expectedBlocked = true),
        SecurityScenario("capability.bypass", "Capability ausente nega execução", "policy", expectedBlocked = true),
        SecurityScenario("evidence.tampering", "Evidência alterada falha no gate", "integrity", expectedBlocked = true)
    )

    fun validate(scenarios: List<SecurityScenario>): List<String> {
        val errors = mutableListOf<String>()
        val duplicateIds = scenarios.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys
        duplicateIds.forEach { errors += "cenário duplicado: $it" }
        scenarios.filter { !it.expectedBlocked && it.category in setOf("secrets", "filesystem", "execution", "network", "policy", "integrity") }
            .forEach { errors += "cenário baseline deve ser bloqueante: ${it.id}" }
        return errors
    }
}
