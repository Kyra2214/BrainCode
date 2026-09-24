package com.brain.plan

import com.brain.capability.BuiltInAgentDefinitions
import com.brain.capability.CapabilityRegistry
import com.brain.capability.CapabilityAvailability
import com.brain.capability.CapabilityCategory
import com.brain.capability.CapabilityDefinition
import com.brain.capability.CapabilityProvenance
import com.brain.gateway.ActionLifecycle
import com.brain.memory.SkillCandidate
import com.brain.observability.ExecutionTrace
import com.brain.observability.InMemoryTraceSink
import com.brain.observability.TraceStage
import com.brain.planner.PassoPlano
import com.brain.planner.PlanoExecucao
import com.brain.skill.SkillManifest
import com.brain.skill.SkillValidationEvidence
import com.brain.skill.SkillValidator
import com.brain.skill.SkillRegistry
import com.brain.skill.TrustLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanGapCoverageTest {
    @Test
    fun `execution plan expõe tasks assumptions policies e fallback`() {
        val plan = PlanoExecucao(
            "auditar", listOf(PassoPlano("read", "github.read", "evidência lida")),
            assumptions = setOf("repo autenticado"), policies = setOf("read-only"), fallback = "report-only"
        )
        assertEquals("read", plan.tasks.single().id)
        assertEquals(setOf("repo autenticado"), plan.assumptions)
        assertEquals("report-only", plan.fallback)
    }

    @Test
    fun `agents builtin sao candidates declarativos`() {
        val registry = CapabilityRegistry(listOf(BuiltInAgentDefinitions.researchAgent(), BuiltInAgentDefinitions.codeAgent()))
        assertEquals(CapabilityCategory.AGENT, registry.getById("agent.research")?.category)
        assertTrue(registry.findByCapability("code.test").isNotEmpty())
    }

    @Test
    fun `skill validator so promove apos sandbox test e critic`() {
        val manifest = SkillManifest("validated.skill", "Validated", "1.0.0", "test", "test", setOf("test.run"), trustLevel = TrustLevel.CORE)
        val candidate = SkillCandidate("knowledge-1", manifest, listOf("evidence-1"))
        val validator = SkillValidator(
            sandbox = { skill, _ -> SkillValidationEvidence(skill.id, true, true, false, listOf("sandbox", "tests")) },
            critic = { it.evidence.contains("sandbox") }
        )
        val evidence = validator.validate(candidate)
        assertTrue(evidence.passed)
        val registry = SkillRegistry()
        assertTrue(validator.promote(candidate, evidence, registry).manifest.id == "validated.skill")
    }

    @Test
    fun `trace renderiza cadeia tecnica e lifecycle enum cobre estados do plano`() {
        val sink = InMemoryTraceSink()
        val trace = ExecutionTrace(sink)
        trace.record("t", TraceStage.TASK, "created", "task-1")
        trace.record("t", TraceStage.POLICY, "allow", "decision-1")
        trace.record("t", TraceStage.EVIDENCE, "recorded", "e-1")
        val rendered = trace.render(sink.all("t"))
        assertTrue(rendered.contains("TASK(created):task-1"))
        assertTrue(rendered.contains("EVIDENCE(recorded):e-1"))
        assertTrue(ActionLifecycle.values().contains(ActionLifecycle.BLOCKED))
    }
}
