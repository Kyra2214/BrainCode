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
import com.brain.research.ResearchResult
import com.brain.research.SearchProvider
import com.brain.research.WebProviderSet
import com.brain.research.WebResearchAgent
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `pergunta de data conhecida continua respondendo a data local`() {
        val executor = ChatResponseExecutor(Clock.fixed(Instant.parse("2026-09-21T18:30:00Z"), ZoneId.of("UTC")))
        val result = executor.execute(request("Que dia é hoje?"), capability, decision)

        assertTrue(result.success)
        assertTrue(result.result!!.contains("Hoje é 21/09/2026"))
        assertTrue(result.evidence.any { it.startsWith("chat:clock:") })
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

    @Test
    fun `pergunta factual sem pesquisa nao finge ter entendido`() {
        val result = ChatResponseExecutor().execute(request("Qual a temperatura de Rio das Ostras hoje"), capability, decision)

        assertTrue(result.success)
        assertTrue(result.evidence.contains("chat:conversation:local-miss"))
        assertTrue(result.result!!.contains("Não tenho conhecimento suficiente"))
        assertFalse(result.result!!.contains("quer que eu pesquise agora", ignoreCase = true))
    }

    @Test
    fun `tempo hoje em local e pergunta factual e nao data`() {
        val result = ChatResponseExecutor().execute(request("tempo hoje em rio das ostras"), capability, decision)

        assertTrue(result.success)
        assertTrue(result.evidence.contains("chat:conversation:local-miss"))
        assertFalse(result.evidence.any { it.startsWith("chat:clock:") })
        assertFalse(result.result!!.startsWith("Hoje é"))
    }

    @Test
    fun `hoje seguido de texto comum nao responde data`() {
        listOf("hoje eu vou trabalhar", "hoje espero que chova").forEach { prompt ->
            val result = ChatResponseExecutor().execute(request(prompt), capability, decision)

            assertTrue(prompt, result.success)
            assertTrue(prompt, result.evidence.contains("chat:conversation"))
            assertFalse(prompt, result.evidence.any { it.startsWith("chat:clock:") })
        }
    }

    @Test
    fun `needs clarification produz pergunta via chat respond`() {
        val result = ChatResponseExecutor().execute(
            request("Qual estilo visual você prefere?", clarification = true), capability, decision
        )

        assertTrue(result.success)
        assertTrue(result.result!!.contains("esclarecimento"))
        assertTrue(result.evidence.contains("chat:clarification-question"))
    }

    @Test
    fun `local hit nao chama WebResearch`() {
        var calls = 0
        val research = WebResearchAgent(WebProviderSet(search = listOf(SearchProvider { calls++; error("não deveria pesquisar") })))
        val result = ChatResponseExecutor(conversationEngine = engine(), researchFallback = research)
            .execute(request("o que é um disjuntor?"), capability, decision)

        assertTrue(result.result!!.contains("disjuntor", ignoreCase = true))
        assertEquals(0, calls)
        assertFalse(result.provenance.contains("research:auto-fallback-after-local-miss"))
    }

    @Test
    fun `local miss pesquisa automaticamente sem pedir aprovação`() {
        var calls = 0
        val research = WebResearchAgent(WebProviderSet(search = listOf(SearchProvider { request ->
            calls++
            assertTrue(request.query.contains("Kotlin", ignoreCase = true))
            Result.success(listOf(ResearchResult("q", "test", "Kotlin", "https://kotlinlang.org", "Kotlin é uma linguagem de programação", java.time.Instant.now(), .9)))
        })))
        val result = ChatResponseExecutor(conversationEngine = engine(), researchFallback = research)
            .execute(request("O que é Kotlin?"), capability, decision)

        assertTrue(result.success)
        assertEquals(1, calls)
        assertTrue(result.result!!.contains("Kotlin é uma linguagem"))
        assertFalse(result.result!!.contains("Quer que eu pesquise", ignoreCase = true))
        assertTrue(result.provenance.contains("research:auto-fallback-after-local-miss"))
        assertTrue(result.evidence.contains("chat:conversation"))
        assertTrue(result.evidence.contains("chat:secretary:block"))
        assertTrue(result.evidence.contains("chat:websearch:executed"))
        assertTrue(result.evidence.contains("chat:websearch:evidence"))
        assertTrue(result.evidence.contains("chat:conversation:synthesis"))
        assertTrue(result.evidence.contains("chat:secretary:accept"))
    }

    @Test
    fun `local miss fora do molde de pergunta tambem pesquisa`() {
        var calls = 0
        val research = WebResearchAgent(WebProviderSet(search = listOf(SearchProvider { request ->
            calls++
            assertTrue(request.query.contains("Android", ignoreCase = true))
            Result.success(listOf(ResearchResult("q", "test", "Android", "https://developer.android.com", "Android é um sistema operacional móvel", java.time.Instant.now(), .9)))
        })))
        val result = ChatResponseExecutor(conversationEngine = engine(), researchFallback = research)
            .execute(request("Tecnologia usada no Android moderno"), capability, decision)

        assertTrue(result.success)
        assertEquals(1, calls)
        assertTrue(result.provenance.contains("research:auto-fallback-after-local-miss"))
    }

    private fun engine(): NoInferenceConversationEngine = NoInferenceConversationEngine(assets = { path ->
        java.io.File("src/main/assets/$path").readText()
    })

    private fun request(prompt: String, research: String? = null, clarification: Boolean = false): ActionRequest = ActionRequest(
        actionId = "chat-test",
        actor = "android-app",
        capability = "chat.respond",
        parameters = buildMap { put("parameter.0", prompt); research?.let { put("parameter.1", it) }; if (clarification) put("parameter.clarification", "clarification.status=NEEDS_CLARIFICATION") },
        context = PolicyContext("run-chat", "chat", "android-app")
    )
}
