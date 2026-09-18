package com.sandbox.agent

import com.brain.capability.CapabilityAvailability
import com.brain.capability.CapabilityCategory
import com.brain.capability.CapabilityDefinition
import com.brain.capability.CapabilityProvenance
import com.brain.capability.CapabilityRegistry

/**
 * Agent amarrado do BrainCode. O Brain entrega uma missão já definida e o
 * Agent só pode operar dentro das capabilities declaradas pelo seu perfil.
 * A execução é bounded: limite de chamadas, deadline e parada no primeiro erro.
 */
interface BoundAgent {
    val id: String
    val capabilities: Set<AgentCapability>
    fun accepts(mission: AgentMission): Boolean = mission.requiredCapabilities.all { it in capabilities }
    fun execute(mission: AgentMission, context: AgentExecutionContext): AgentResult
}

enum class AgentCapability { TERMINAL_RESEARCH, WEB_SEARCH, GITHUB, FILE_READ, FILE_WRITE, CODE_BUILD, CODE_TEST, WORKSPACE, GIT, MEDIA }

data class AgentMission(
    val id: String,
    val objective: String,
    val requiredCapabilities: Set<AgentCapability>,
    val parameters: Map<String, String> = emptyMap(),
    val maxCapabilityCalls: Int = requiredCapabilities.size.coerceAtLeast(1),
    val deadlineEpochMillis: Long? = null
) {
    init {
        require(id.isNotBlank()) { "id da missão é obrigatório" }
        require(objective.isNotBlank()) { "objetivo da missão é obrigatório" }
        require(requiredCapabilities.isNotEmpty()) { "missão precisa de ao menos uma capability" }
        require(maxCapabilityCalls > 0) { "maxCapabilityCalls deve ser positivo" }
        require(maxCapabilityCalls >= requiredCapabilities.size) { "maxCapabilityCalls não pode ser menor que o número de capabilities requeridas" }
    }
}

data class AgentEvidence(
    val kind: String,
    val value: String,
    val source: String? = null,
    val ok: Boolean = true
)

data class AgentResult(
    val agentId: String,
    val missionId: String,
    val success: Boolean,
    val summary: String,
    val evidence: List<AgentEvidence> = emptyList(),
    val calls: Int = evidence.size
)

interface AgentExecutionContext {
    fun invokeCapability(capability: AgentCapability, parameters: Map<String, String>): AgentEvidence
}

/** Guard determinístico compartilhado por todos os Agents. */
internal object AgentExecutionGuard {
    fun run(agent: BoundAgent, mission: AgentMission, context: AgentExecutionContext): AgentResult {
        if (!agent.accepts(mission)) return AgentResult(agent.id, mission.id, false, "Missão exige capabilities não autorizadas pelo Agent.")
        val evidence = mutableListOf<AgentEvidence>()
        for (capability in mission.requiredCapabilities) {
            if (evidence.size >= mission.maxCapabilityCalls) return AgentResult(agent.id, mission.id, false, "Limite de chamadas da missão atingido antes da conclusão.", evidence, evidence.size)
            mission.deadlineEpochMillis?.let { deadline ->
                if (System.currentTimeMillis() >= deadline) return AgentResult(agent.id, mission.id, false, "Deadline da missão atingido antes da conclusão.", evidence, evidence.size)
            }
            val result = runCatching { context.invokeCapability(capability, mission.parameters) }.getOrElse { error ->
                AgentEvidence("capability-error", error.message ?: error::class.simpleName.orEmpty(), capability.name, false)
            }
            val normalized = if (result.source == null) result.copy(source = capability.name) else result
            evidence += normalized
            if (!normalized.ok) return AgentResult(agent.id, mission.id, false, "Capability " + capability.name + " falhou; missão interrompida.", evidence, evidence.size)
        }
        val complete = mission.requiredCapabilities.all { capability -> evidence.any { it.ok && it.source == capability.name } }
        return AgentResult(agent.id, mission.id, complete, if (complete) "Missão concluída com evidência positiva para todas as capabilities." else "Missão não produziu evidência positiva suficiente.", evidence, evidence.size)
    }
}

class AgentRegistry(agents: List<BoundAgent>) {
    private val byId = agents.groupBy { it.id }.also { groups -> require(groups.values.all { it.size == 1 }) { "ids de Agent duplicados" } }.mapValues { it.value.single() }
    fun get(id: String): BoundAgent? = byId[id]
    fun findFor(mission: AgentMission): List<BoundAgent> = byId.values.filter { it.accepts(mission) }
    fun ids(): List<String> = byId.keys.sorted()
    fun publishTo(registry: CapabilityRegistry) {
        byId.values.forEach { agent -> registry.register(CapabilityDefinition(
            id = "agent." + agent.id, name = agent.id, description = "Agent bounded sem LLM próprio",
            category = CapabilityCategory.AGENT, ownerId = agent.id, origin = "agent-registry",
            providedCapabilities = agent.capabilities.map { it.name.lowercase() }.toSet(),
            availability = CapabilityAvailability.AVAILABLE,
            provenance = listOf(CapabilityProvenance(agent.id, "bounded-agent", evidence = "mission + allowed capabilities"))
        )) }
    }
}

class ResearchAgent : BoundAgent {
    override val id = "research"
    override val capabilities = setOf(AgentCapability.TERMINAL_RESEARCH, AgentCapability.WEB_SEARCH, AgentCapability.GITHUB)
    override fun execute(mission: AgentMission, context: AgentExecutionContext): AgentResult = AgentExecutionGuard.run(this, mission, context)
}

class CodeAgent : BoundAgent {
    override val id = "code"
    override val capabilities = setOf(AgentCapability.FILE_READ, AgentCapability.FILE_WRITE, AgentCapability.WORKSPACE, AgentCapability.CODE_BUILD, AgentCapability.CODE_TEST, AgentCapability.GIT)
    override fun execute(mission: AgentMission, context: AgentExecutionContext): AgentResult = AgentExecutionGuard.run(this, mission, context)
}

object BuiltInAgents { val all: List<BoundAgent> = listOf(ResearchAgent(), CodeAgent()) }