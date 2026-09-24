package com.brain.router

import java.net.URI

/** Proveniência declarada para um modelo descoberto fora do catálogo local. */
data class ApiDiscoverySource(
    val id: String,
    val name: String,
    val official: Boolean,
    val priority: Int
) {
    init {
        require(id.isNotBlank() && name.isNotBlank())
        require(priority >= 0)
    }
}

/** Candidato imutável; a camada de discovery nunca executa chamadas de rede. */
data class ApiDiscoveryCandidate(
    val model: ProviderModel,
    val source: ApiDiscoverySource,
    val documentationUrl: String,
    val confidence: Double,
    val capabilities: Set<String> = emptySet(),
    val active: Boolean = true
) {
    init {
        require(isSafeHttps(documentationUrl)) { "Documentation URL must be HTTPS without credentials or fragments" }
        require(confidence in 0.0..1.0)
    }
}

enum class ApiDiscoveryDecision { ACCEPT, REVIEW_REQUIRED, REJECT }

data class ApiDiscoveryFinding(
    val candidate: ApiDiscoveryCandidate,
    val decision: ApiDiscoveryDecision,
    val reasons: List<String>
)

data class ApiDiscoveryReport(
    val accepted: List<ProviderModel>,
    val findings: List<ApiDiscoveryFinding>
) {
    val reviewRequired: List<ProviderModel>
        get() = findings.filter { it.decision == ApiDiscoveryDecision.REVIEW_REQUIRED }.map { it.candidate.model }

    val rejected: List<ProviderModel>
        get() = findings.filter { it.decision == ApiDiscoveryDecision.REJECT }.map { it.candidate.model }
}

/**
 * Normaliza e valida propostas de catálogo vindas de fontes externas.
 * Discovery é deliberadamente separado de transporte: o chamador fornece os
 * candidatos e decide como coletá-los (API, arquivo ou job agendado).
 */
class ApiDiscoveryEngine(
    private val minimumConfidence: Double = 0.60,
    private val requireOfficialSource: Boolean = true
) {
    init { require(minimumConfidence in 0.0..1.0) }

    fun discover(
        existing: List<ProviderModel>,
        candidates: List<ApiDiscoveryCandidate>
    ): ApiDiscoveryReport {
        val known = existing.map { key(it.providerId, it.modeloId) }.toMutableSet()
        val findings = candidates
            .sortedWith(compareByDescending<ApiDiscoveryCandidate> { it.source.priority }.thenBy { key(it.model.providerId, it.model.modeloId) })
            .map { candidate ->
                val reasons = mutableListOf<String>()
                val identity = key(candidate.model.providerId, candidate.model.modeloId)
                when {
                    !candidate.active -> {
                        reasons += "modelo inativo"
                        ApiDiscoveryFinding(candidate, ApiDiscoveryDecision.REJECT, reasons)
                    }
                    identity in known -> {
                        reasons += "modelo já catalogado"
                        ApiDiscoveryFinding(candidate, ApiDiscoveryDecision.REJECT, reasons)
                    }
                    requireOfficialSource && !candidate.source.official -> {
                        reasons += "fonte não oficial exige revisão"
                        ApiDiscoveryFinding(candidate, ApiDiscoveryDecision.REVIEW_REQUIRED, reasons)
                    }
                    candidate.confidence < minimumConfidence -> {
                        reasons += "confiança abaixo do mínimo"
                        ApiDiscoveryFinding(candidate, ApiDiscoveryDecision.REVIEW_REQUIRED, reasons)
                    }
                    else -> {
                        reasons += "fonte confiável e identidade inédita"
                        known += identity
                        ApiDiscoveryFinding(candidate, ApiDiscoveryDecision.ACCEPT, reasons)
                    }
                }
            }
        return ApiDiscoveryReport(
            accepted = findings.filter { it.decision == ApiDiscoveryDecision.ACCEPT }.map { it.candidate.model },
            findings = findings
        )
    }

    private fun key(providerId: String, modelId: String) = "${providerId.trim().lowercase()}::${modelId.trim().lowercase()}"
}

private fun isSafeHttps(value: String): Boolean = runCatching {
    val uri = URI(value.trim())
    uri.scheme.equals("https", ignoreCase = true) &&
        !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null &&
        !uri.host.equals("localhost", ignoreCase = true) &&
        !uri.host.startsWith("127.") && !uri.host.startsWith("10.") &&
        !uri.host.startsWith("192.168.")
}.getOrDefault(false)
