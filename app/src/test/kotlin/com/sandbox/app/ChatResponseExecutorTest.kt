package com.sandbox.app

import com.brain.capability.CapabilityDefinition
import com.brain.capability.CapabilityCategory
import com.brain.capability.CapabilityAvailability
import com.brain.capability.CapabilityProvenance
import com.brain.execution.RiskClass
import com.brain.gateway.ActionRequest
import com.brain.policy.ApprovalRequired
import com.brain.policy.Decision
import com.brain.policy.PolicyContext
import com.brain.policy.PolicyDecision
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatResponseExecutorTest {
    private val capability = CapabilityDefinition(
        id = "chat.respond",
        name = "chat.respond",
        description = "resposta conversacional local",
        category = CapabilityCategory.INTERNAL,
        ownerId = "test",
        origin = "test",
        availability = CapabilityAvailability.AVAILABLE,
        provenance = listOf(CapabilityProvenance("test", "test"))
    )
    private val decision = PolicyDecision(
        decisionId = "d1", runId = "r1", taskId = "t1", actor = "test", capability = "chat.respond",
        riskClass = RiskClass.LOW, decision = Decision.ALLOW, approvalRequired = ApprovalRequired.NONE,
        sandboxRequired = false, networkAllowed = false, filesystemRoots = emptyList(), budget = emptyMap(),
        expiresAt = Instant.now().plusSeconds(60).toString(), reason = "ok"
    )

    @Test
    fun `responde hora local sem API`() {
        val executor = ChatResponseExecutor(Clock.fixed(Instant.parse("2026-09-20T18:30:00Z"), ZoneId.of("UTC")))
        val result = executor.execute(request("Que horas são?"), capability, decision)

        assertTrue(result.success)
        assertTrue(result.result!!.contains("18:30"))
        assertTrue(result.evidence.any { it.startsWith("chat:clock:") })
        assertTrue(result.provenance.contains("app:ChatResponseExecutor"))
    }

    @Test
    fun `organiza contexto local sem criar nem executar`() {
        val executor = ChatResponseExecutor(
            clock = Clock.fixed(Instant.parse("2026-09-20T18:30:00Z"), ZoneId.of("UTC")),
            contextProvider = { ConversationContext(idea = "aplicativo de notas", requirements = listOf("offline"), pending = listOf("decidir autenticação")) }
        )
        val result = executor.execute(request("Organize o que já sabemos"), capability, decision)

        assertTrue(result.success)
        assertTrue(result.result!!.contains("aplicativo de notas"))
        assertTrue(result.result!!.contains("offline"))
        assertTrue(result.result!!.contains("autenticação"))
        assertTrue(result.evidence.contains("chat:context:read-only"))
    }

    @Test
    fun `inclui pesquisa recebida como evidencia sem inventar fonte`() {
        val executor = ChatResponseExecutor()
        val result = executor.execute(request("Explique o resumo", "Pesquisa web concluída: Fonte X (example.org): trecho comprovado"), capability, decision)

        assertTrue(result.success)
        assertTrue(result.result!!.contains("Fonte X"))
        assertTrue(result.evidence.contains("chat:research-context-included"))
        assertTrue(result.provenance.contains("source:dependency:network.research"))
    }

    private fun request(prompt: String, research: String? = null): ActionRequest = ActionRequest(
        actionId = "chat-test",
        actor = "android-app",
        capability = "chat.respond",
        parameters = buildMap { put("parameter.0", prompt); research?.let { put("parameter.1", it) } },
        context = PolicyContext("run-chat", "chat", "android-app")
    )
}
