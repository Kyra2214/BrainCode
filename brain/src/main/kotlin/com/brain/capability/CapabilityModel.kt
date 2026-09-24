package com.brain.capability

import com.brain.execution.RiskClass
import java.time.Instant

/**
 * Origem funcional de uma capability. A categoria descreve o tipo do recurso,
 * não concede autorização para executá-lo.
 */
enum class CapabilityCategory {
    API,
    PROVIDER,
    AGENT,
    SKILL,
    TOOL,
    COMMAND,
    SANDBOX,
    WORKFLOW,
    INTERNAL
}

enum class CapabilityStatus { ACTIVE, DISABLED, DEPRECATED, REVOKED }

enum class CapabilityAvailability { AVAILABLE, UNAVAILABLE, UNKNOWN }

enum class CostClass {
    FREE,
    LOW,
    MEDIUM,
    HIGH,
    UNKNOWN;

    internal val rank: Int
        get() = when (this) {
            FREE -> 0
            LOW -> 1
            MEDIUM -> 2
            HIGH -> 3
            UNKNOWN -> Int.MAX_VALUE
        }
}

data class CapabilityParameter(
    val name: String,
    val schema: String,
    val required: Boolean = false,
    val description: String? = null
) {
    init {
        require(name.isNotBlank()) { "nome do parâmetro é obrigatório" }
        require(schema.isNotBlank()) { "schema do parâmetro é obrigatório" }
    }
}

/** Proveniência é declarativa; ela registra de onde veio o metadado. */
data class CapabilityProvenance(
    val sourceId: String,
    val sourceType: String,
    val uri: String? = null,
    val evidence: String? = null,
    val observedAt: Instant = Instant.now()
) {
    init {
        require(sourceId.isNotBlank()) { "sourceId da proveniência é obrigatório" }
        require(sourceType.isNotBlank()) { "sourceType da proveniência é obrigatório" }
        uri?.let { require(it.isNotBlank()) { "URI de proveniência não pode ser vazia" } }
    }
}

/**
 * Modelo universal para qualquer capacidade executável ou selecionável pelo
 * Brain. O modelo descreve o recurso; PolicyBroker continua sendo a autoridade
 * de autorização e ActionGateway será a autoridade de entrada na execução.
 */
data class CapabilityDefinition(
    val id: String,
    val name: String,
    val description: String,
    val category: CapabilityCategory,
    val ownerId: String,
    val origin: String,
    val parameters: List<CapabilityParameter> = emptyList(),
    val inputSchema: String? = null,
    val outputSchema: String? = null,
    val requiredCapabilities: Set<String> = emptySet(),
    val providedCapabilities: Set<String> = emptySet(),
    val requiredPermissions: Set<String> = emptySet(),
    val risk: RiskClass = RiskClass.LOW,
    val cost: CostClass = CostClass.UNKNOWN,
    val estimatedLatencyMs: Long? = null,
    val reliability: Double = 0.5,
    val quality: Double = 0.5,
    val supportsFiles: Boolean = false,
    val supportsWeb: Boolean = false,
    val supportsCode: Boolean = false,
    val supportsReasoning: Boolean = false,
    val availability: CapabilityAvailability = CapabilityAvailability.UNKNOWN,
    val version: String = "1.0.0",
    val status: CapabilityStatus = CapabilityStatus.ACTIVE,
    val provenance: List<CapabilityProvenance>,
    val providerIds: Set<String> = emptySet(),
    val metadata: Map<String, String> = emptyMap()
) {
    init {
        require(ID_PATTERN.matches(id)) { "id de capability inválido: $id" }
        require(name.isNotBlank()) { "nome da capability é obrigatório" }
        require(description.isNotBlank()) { "descrição da capability é obrigatória" }
        require(ownerId.isNotBlank()) { "ownerId da capability é obrigatório" }
        require(origin.isNotBlank()) { "origem da capability é obrigatória" }
        require(version.isNotBlank()) { "versão da capability é obrigatória" }
        require(reliability in 0.0..1.0) { "reliability deve estar entre 0 e 1" }
        require(quality in 0.0..1.0) { "quality deve estar entre 0 e 1" }
        estimatedLatencyMs?.let { require(it >= 0) { "latência estimada não pode ser negativa" } }
        require(id !in requiredCapabilities) { "capability não pode depender de si mesma" }
        require(requiredCapabilities.none { it.isBlank() }) { "capability requerida não pode ser vazia" }
        require(providedCapabilities.none { it.isBlank() }) { "capability fornecida não pode ser vazia" }
        require(requiredPermissions.none { it.isBlank() }) { "permissão requerida não pode ser vazia" }
        require(providerIds.none { it.isBlank() }) { "providerId não pode ser vazio" }
        require(provenance.isNotEmpty()) { "capability precisa declarar proveniência" }
    }

    /** Nome e aliases explícitos que esta entrada pode satisfazer no registry. */
    fun provides(capability: String): Boolean = id == capability || capability in providedCapabilities

    fun isDiscoverable(): Boolean =
        status == CapabilityStatus.ACTIVE && availability == CapabilityAvailability.AVAILABLE

    companion object {
        private val ID_PATTERN = Regex("^[a-zA-Z0-9][a-zA-Z0-9._-]*$")
    }
}

/** Consulta estrutural do registry; não executa ranking de policy nem ações. */
data class CapabilityQuery(
    val categories: Set<CapabilityCategory> = emptySet(),
    val requiredCapabilities: Set<String> = emptySet(),
    val providerIds: Set<String> = emptySet(),
    val riskAtMost: RiskClass? = null,
    val costAtMost: CostClass? = null,
    val supportsFiles: Boolean = false,
    val supportsWeb: Boolean = false,
    val supportsCode: Boolean = false,
    val supportsReasoning: Boolean = false,
    val availableOnly: Boolean = true
) {
    init {
        require(requiredCapabilities.none { it.isBlank() }) { "capability requerida não pode ser vazia" }
        require(providerIds.none { it.isBlank() }) { "providerId não pode ser vazio" }
    }
}

internal fun RiskClass.rank(): Int = when (this) {
    RiskClass.READ_ONLY -> 0
    RiskClass.LOW -> 1
    RiskClass.MEDIUM -> 2
    RiskClass.HIGH -> 3
    RiskClass.CRITICAL -> 4
}
