package com.sandbox.app

import com.brain.capability.CapabilityAvailability
import com.brain.capability.CapabilityCategory
import com.brain.capability.CapabilityDefinition
import com.brain.capability.CapabilityProvenance
import com.brain.execution.RiskClass
import com.brain.gateway.ActionRequest
import com.brain.policy.ApprovalRequired
import com.brain.policy.Decision
import com.brain.policy.PolicyContext
import com.brain.policy.PolicyDecision
import com.brain.research.ResearchResult
import com.brain.research.WebResearchProvider
import java.time.Instant
import org.junit.Assert.assertTrue
import org.junit.Test

class WebResearchExecutorTest {

    private val capability = CapabilityDefinition(
        id = "sandbox.info", name = "sandbox.info", description = "t", category = CapabilityCategory.SANDBOX,
        ownerId = "test", origin = "test", providedCapabilities = setOf("network.research"),
        availability = CapabilityAvailability.AVAILABLE, provenance = listOf(CapabilityProvenance("test", "test"))
    )
    private val decision = PolicyDecision(
        decisionId = "d1", runId = "r1", taskId = "t1", actor = "test", capability = "sandbox.info",
        riskClass = RiskClass.LOW, decision = Decision.ALLOW, approvalRequired = ApprovalRequired.NONE,
        sandboxRequired = false, networkAllowed = true, filesystemRoots = emptyList(), budget = emptyMap(),
        expiresAt = Instant.now().plusSeconds(60).toString(), reason = "ok"
    )

    private fun request(query: String) = ActionRequest(
        actionId = "a1", actor = "test", capability = "sandbox.info",
        parameters = mapOf("parameter.0" to query),
        context = PolicyContext(runId = "r1", taskId = "t1", actor = "test")
    )

    @Test fun `pesquisa indisponivel nunca falha o passo, apenas informa`() {
        val provider = WebResearchProvider { _, _ -> Result.failure(IllegalStateException("sem conexão")) }
        val executor = WebResearchExecutor(provider)
        val execution = executor.execute(request("técnicas atuais de fotografia"), capability, decision)
        assertTrue("passo nunca deve falhar duro por falta de rede", execution.success)
        assertTrue(execution.result.orEmpty().contains("indisponível", ignoreCase = true))
    }

    @Test fun `resultados reais preservam fonte titulo url e data`() {
        val agora = Instant.now()
        val provider = WebResearchProvider { query, _ ->
            Result.success(listOf(ResearchResult(query, "exemplo.com", "Guia X", "https://exemplo.com/x", "conteúdo relevante", agora)))
        }
        val executor = WebResearchExecutor(provider)
        val execution = executor.execute(request("como configurar X"), capability, decision)
        assertTrue(execution.success)
        assertTrue(execution.result.orEmpty().contains("exemplo.com"))
        assertTrue(execution.evidence.any { it.contains("url=https://exemplo.com/x") })
        assertTrue(execution.researchSources.single().title == "Guia X")
        assertTrue(execution.researchSources.single().url == "https://exemplo.com/x")
    }

    @Test fun `zero resultados nao vira erro duro`() {
        val provider = WebResearchProvider { _, _ -> Result.success(emptyList()) }
        val executor = WebResearchExecutor(provider)
        val execution = executor.execute(request("assunto muito obscuro"), capability, decision)
        assertTrue(execution.success)
    }
}
