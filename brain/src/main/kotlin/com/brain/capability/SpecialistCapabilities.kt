package com.brain.capability

/**
 * Ponte entre as duas fontes de especialistas da Porta 3, para registrá-las UMA vez no
 * [CapabilityRegistry] sem catálogos paralelos:
 *
 *  - [BuiltInAgentDefinitions]: fonte dos campos de policy (id, risk, providedCapabilities).
 *  - [SpecialistDefinitions]: fonte do conteúdo (descrição, fase, entrada, saída, responsabilidades).
 *
 * Registrar um especialista no registry NÃO o torna executável nem autorizado: esses agentes são
 * declarativos (availability = UNAVAILABLE, sem executor). O PolicyBroker continua negando qualquer
 * capability que o caller não conceda explicitamente ao ator — por isso o caller deve montar as
 * listas de grant com [grantable], que exclui estas entradas.
 */
object SpecialistCapabilities {
    private const val BUILTIN_OWNER = "brain-builtin"

    /**
     * Capability executável (ao contrário dos `agent.*`, que são declarativos): envia o prompt de uma
     * tarefa da Porta 3 ao provider autorizado em nome do especialista responsável. Passa por
     * PolicyBroker/ActionGateway como qualquer outra; só existe no registry se o app fornecer o executor.
     */
    const val EXECUTE_CAPABILITY = "specialist.execute"

    fun executionCapability(): CapabilityDefinition = CapabilityDefinition(
        id = EXECUTE_CAPABILITY,
        name = "SpecialistExecution",
        description = "Executa o prompt de uma tarefa do roadmap pelo especialista responsável, via provider autorizado",
        category = CapabilityCategory.AGENT,
        ownerId = BUILTIN_OWNER,
        origin = BUILTIN_OWNER,
        supportsReasoning = true,
        availability = CapabilityAvailability.AVAILABLE,
        provenance = listOf(CapabilityProvenance(BUILTIN_OWNER, "specialist-execution"))
    )

    /** Uma [CapabilityDefinition] por especialista, na ordem de [SpecialistDefinitions.getAllSpecialists]. */
    fun definitions(): List<CapabilityDefinition> {
        val policySource = (BuiltInAgentDefinitions.boundedSpecialists() + BuiltInAgentDefinitions.codeAgent())
            .associateBy { it.id }
        return SpecialistDefinitions.getAllSpecialists().map { spec ->
            val base = policySource[spec.capabilityId]
                ?: error("especialista sem definição de policy em BuiltInAgentDefinitions: ${spec.capabilityId}")
            base.copy(
                description = spec.descricao,
                metadata = base.metadata + mapOf(
                    "displayName" to spec.nome,
                    "phase" to spec.faseAplicavel,
                    "input" to spec.entrada,
                    "output" to spec.saida,
                    "responsibilities" to spec.responsabilidades.joinToString("; ")
                ),
                provenance = base.provenance + CapabilityProvenance(BUILTIN_OWNER, "specialist-definition")
            )
        }
    }

    fun isSpecialist(definition: CapabilityDefinition): Boolean =
        definition.category == CapabilityCategory.AGENT &&
            definition.ownerId == BUILTIN_OWNER &&
            definition.provenance.any { it.sourceType == "specialist-definition" }

    /** Definições que podem entrar nas listas de grant do PolicyBroker (tudo, menos os especialistas declarativos). */
    fun grantable(all: Collection<CapabilityDefinition>): List<CapabilityDefinition> =
        all.filterNot(::isSpecialist)

    /**
     * Junta os especialistas a uma lista inicial de definições sem quebrar por id duplicado:
     * se um provider já registrou o mesmo id, a definição existente prevalece.
     */
    fun mergeInto(existing: List<CapabilityDefinition>): List<CapabilityDefinition> {
        val taken = existing.map { it.id }.toSet()
        return existing + definitions().filter { it.id !in taken }
    }
}
