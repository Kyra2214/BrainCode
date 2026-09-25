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
import com.brain.research.LegacySearchProviderAdapter
import com.brain.research.ResearchRequest
import com.brain.research.ResearchResult
import com.brain.research.WebProviderSet
import com.brain.research.WebResearchAgent
import com.brain.research.WebResearchProvider
import java.time.Instant
import org.junit.Assert.assertEquals
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
        // Sem fetch provider aqui de propósito: este teste verifica o mapeamento de
        // fonte/título/URL/evidência do executor, não a busca de rede real (ver o
        // teste de enriquecimento abaixo para isso, com um FetchProvider fake).
        val executor = WebResearchExecutor(
            provider,
            researchAgent = WebResearchAgent(WebProviderSet(search = listOf(LegacySearchProviderAdapter(provider))))
        )
        val execution = executor.execute(request("como configurar X"), capability, decision)
        assertTrue(execution.success)
        assertTrue(execution.result.orEmpty().contains("exemplo.com"))
        assertTrue(execution.evidence.any { it.contains("url=https://exemplo.com/x") })
        assertTrue(execution.researchSources.single().title == "Guia X")
        assertTrue(execution.researchSources.single().url == "https://exemplo.com/x")
    }

    @Test fun `conteudo real da pagina substitui o snippet da SERP quando o fetch funciona`() {
        val agora = Instant.now()
        val provider = WebResearchProvider { query, _ ->
            Result.success(listOf(ResearchResult(query, "previsaoagora.com.br", "Previsão Macaé", "https://previsaoagora.com.br/macae", "Guia de leitura: a previsão do tempo ganha sentido quando...", agora)))
        }
        val fetcher = com.brain.research.FetchProvider { url, req ->
            Result.success(ResearchResult(req.query, "previsaoagora.com.br", "", url, "Macaé hoje: máxima de 29°C, mínima de 22°C, parcialmente nublado.", agora))
        }
        val executor = WebResearchExecutor(
            provider,
            researchAgent = WebResearchAgent(
                WebProviderSet(search = listOf(LegacySearchProviderAdapter(provider)), fetch = listOf(fetcher))
            )
        )
        val execution = executor.execute(request("qual a temperatura em Macaé"), capability, decision)
        val fonte = execution.researchSources.single()
        assertTrue("relevantContent deve vir da página real, não do teaser da SERP", fonte.relevantContent.contains("29°C"))
        assertTrue(fonte.relevantContent.contains("Macaé"))
        assertTrue("confidence deve refletir a sobreposição real com a pergunta", fonte.confidence > 0.0)
    }

    @Test fun `fetch indisponivel cai de volta pro snippet sem quebrar o passo`() {
        val agora = Instant.now()
        val provider = WebResearchProvider { query, _ ->
            Result.success(listOf(ResearchResult(query, "exemplo.com", "Guia X", "https://exemplo.com/x", "conteúdo relevante do snippet", agora)))
        }
        val fetcherFalho = com.brain.research.FetchProvider { _, _ -> Result.failure(IllegalStateException("timeout")) }
        val executor = WebResearchExecutor(
            provider,
            researchAgent = WebResearchAgent(
                WebProviderSet(search = listOf(LegacySearchProviderAdapter(provider)), fetch = listOf(fetcherFalho))
            )
        )
        val execution = executor.execute(request("como configurar X"), capability, decision)
        assertTrue(execution.success)
        assertEquals("conteúdo relevante do snippet", execution.researchSources.single().relevantContent)
    }

    @Test fun `zero resultados nao vira erro duro`() {
        val provider = WebResearchProvider { _, _ -> Result.success(emptyList()) }
        val executor = WebResearchExecutor(provider)
        val execution = executor.execute(request("assunto muito obscuro"), capability, decision)
        assertTrue(execution.success)
    }
}
