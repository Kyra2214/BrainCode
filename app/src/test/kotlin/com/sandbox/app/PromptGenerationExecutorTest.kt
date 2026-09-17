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
import com.brain.capability.CostClass
import com.brain.prompt.PromptLibrary
import com.brain.prompt.PromptTemplate
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cobre os 5 primeiros testes obrigatórios do Prompt Creator Agent:
 * 1) biblioteca encontrada -> adapta; 2) não encontrada -> cria do zero;
 * 3) sem API -> criação continua funcionando; 4) API disponível -> caminho
 * especialista pode ser usado; 5) API inválida/falha -> fallback local continua.
 * O caso do foguete (10) é coberto por LocalPromptCreatorAgentTest a nível de
 * agente; aqui é coberto a nível de executor, ponta a ponta.
 */
class PromptGenerationExecutorTest {

    private class FakePromptLibrary(seed: List<PromptTemplate> = emptyList()) : PromptLibrary {
        val templates = ConcurrentHashMap<String, PromptTemplate>().apply { seed.forEach { put(it.id, it) } }
        override suspend fun buscarPorContexto(contextoDeUso: String): List<PromptTemplate> = templates.values.toList()
        override suspend fun salvarNovaVersao(template: PromptTemplate): String { templates[template.id] = template; return template.id }
        override suspend fun registrarResultado(templateId: String, sucesso: Boolean, custo: Double, tempoMs: Long) = Unit
        override suspend fun aposentar(templateId: String): Boolean {
            val atual = templates[templateId] ?: return false
            templates[templateId] = atual.copy(aposentado = true)
            return true
        }
    }

    private val capability = CapabilityDefinition(
        id = "prompt.library.generate", name = "prompt.library.generate", description = "t", category = CapabilityCategory.SANDBOX,
        ownerId = "test", origin = "test", providedCapabilities = setOf("prompt.library.write"),
        availability = CapabilityAvailability.AVAILABLE, provenance = listOf(CapabilityProvenance("test", "test"))
    )
    private val decision = PolicyDecision(
        decisionId = "d1", runId = "r1", taskId = "t1", actor = "test", capability = "prompt.library.generate",
        riskClass = RiskClass.MEDIUM, decision = Decision.ALLOW, approvalRequired = ApprovalRequired.NONE,
        sandboxRequired = false, networkAllowed = true, filesystemRoots = emptyList(), budget = emptyMap(),
        expiresAt = Instant.now().plusSeconds(60).toString(), reason = "ok"
    )

    private fun request(objetivo: String, actionId: String = "a1", vararg extraParams: String) = ActionRequest(
        actionId = actionId, actor = "test", capability = "prompt.library.generate",
        parameters = (listOf(objetivo) + extraParams).mapIndexed { i, v -> "parameter.$i" to v }.toMap(),
        context = PolicyContext(runId = "r1", taskId = "t1", actor = "test")
    )

    private fun executor(library: PromptLibrary, improver: PromptImprover) = PromptGenerationExecutor(
        promptLibrary = library,
        improver = improver
    )

    private val iaIndisponivel = PromptImprover { _, _ -> error("nenhuma API configurada") }

    // 1) biblioteca encontrada -> adapta
    @Test fun `prompt compativel na biblioteca e usado como referencia e adaptado`() {
        val existente = PromptTemplate(
            id = "foguete-base", versao = 1, finalidade = "prompt de imagem", contextoDeUso = "crie um prompt de um foguete decolando",
            skillRelacionada = "prompt-generation", agenteRelacionado = null, textoTemplate = "Foto de um foguete decolando à noite.",
            taxaSucesso = 0.8, custoMedio = 0.0, tempoMedioMs = 0L, amostrasObservadas = 5
        )
        val library = FakePromptLibrary(listOf(existente))
        val execution = executor(library, iaIndisponivel).execute(request("crie um prompt de um foguete decolando"), capability, decision)

        assertTrue(execution.success)
        assertTrue("deveria citar a referência da biblioteca", execution.evidence.any { it.startsWith("prompt-library:foguete-base") })
        assertTrue(execution.result.orEmpty().contains("biblioteca"))
    }

    // 2) não encontrada -> cria do zero (caso do foguete, item 10 do spec)
    @Test fun `sem prompt compativel cria do zero localmente - caso do foguete sem API`() {
        val library = FakePromptLibrary()
        val pedido = "Crie um prompt para uma fotografia fotorrealista de um foguete espacial decolando, " +
            "com iluminação cinematográfica, céu estrelado ao fundo, alta definição e detalhes realistas."
        val execution = executor(library, iaIndisponivel).execute(request(pedido), capability, decision)

        assertTrue("execução nunca deve falhar por falta de API", execution.success)
        assertTrue(execution.result.orEmpty().contains("localmente"))
        assertTrue(execution.result.orEmpty().contains("cinematográfica"))
        assertFalse("resposta não pode ser a mensagem antiga de bloqueio", execution.result.orEmpty().contains("Configure uma API"))
    }

    // 3) nenhuma API configurada -> criação continua funcionando (reforça o teste acima, checando o motivo do fallback)
    @Test fun `sem API disponivel entrega melhor resultado local com nota transparente`() {
        val library = FakePromptLibrary()
        val execution = executor(library, iaIndisponivel).execute(request("crie um prompt de uma xícara de café"), capability, decision)

        assertTrue(execution.success)
        assertTrue(
            "deveria avisar que a melhoria por IA não estava disponível quando a qualidade local não bate 90%",
            execution.result.orEmpty().contains("IA") || execution.evidence.any { it.startsWith("prompt-quality") }
        )
    }

    // 4) API disponível -> caminho especialista pode ser usado quando o local não bate 90%
    @Test fun `com IA disponivel o resultado da IA e usado quando melhora o score`() {
        val library = FakePromptLibrary()
        val iaFuncional = PromptImprover { atual, _ ->
            "Fotografia profissional detalhada: $atual Composição cuidadosamente balanceada, iluminação de estúdio " +
                "de três pontos, lente 85mm, profundidade de campo rasa, altíssima definição e riqueza de detalhes realistas."
        }
        val execution = executor(library, iaFuncional).execute(request("crie um prompt de uma xícara de café"), capability, decision)

        assertTrue(execution.success)
        assertTrue("origem deveria indicar uso de IA especialista", execution.evidence.any { it.contains("ia-especialista") })
        // improver que não reporta custo real (fake de teste) -> tier padrão FREE, nunca inventado.
        assertEquals(0.0, execution.custo, 0.0001)
    }

    // custo real: quando a IA que melhorou reporta um tier pago, isso chega ao ActionExecution —
    // nunca mais hardcoded em 0.0 independente do que foi realmente usado.
    @Test fun `custo real da IA usada no escalonamento chega ao ActionExecution`() {
        val library = FakePromptLibrary()
        val iaPaga = object : PromptImprover {
            override fun melhorar(promptAtual: String, pedidoOriginal: String): String =
                "Fotografia profissional detalhada: $promptAtual Composição cuidadosamente balanceada, iluminação de estúdio " +
                    "de três pontos, lente 85mm, profundidade de campo rasa, altíssima definição e riqueza de detalhes realistas."
            override fun custoDaUltimaMelhoria(): CostClass = CostClass.MEDIUM
        }
        val execution = executor(library, iaPaga).execute(request("crie um prompt de uma xícara de café"), capability, decision)

        assertTrue(execution.success)
        assertTrue("origem deveria indicar uso de IA especialista", execution.evidence.any { it.contains("ia-especialista") })
        assertTrue("custo real do tier pago deveria ser maior que zero", execution.custo > 0.0)
        assertTrue(execution.evidence.any { it.contains("custo-tier:MEDIUM") })
    }

    // 5) API inválida/erro -> fallback local continua funcionando sem quebrar
    @Test fun `falha da IA no meio do processo nao derruba a execucao`() {
        val library = FakePromptLibrary()
        val iaComErro = PromptImprover { _, _ -> throw java.io.IOException("HTTP 401 - chave inválida") }
        val execution = executor(library, iaComErro).execute(request("crie um prompt de uma xícara de café"), capability, decision)

        assertTrue("erro de API não pode derrubar a execução", execution.success)
    }

    // "melhore esse prompt" -- fluxo de melhoria explícita usando o artefato anterior da conversa
    @Test fun `pedido explicito de melhoria usa o artefato anterior e chama a IA`() {
        val library = FakePromptLibrary()
        var recebeuPromptAnterior = false
        val iaFuncional = PromptImprover { atual, _ -> recebeuPromptAnterior = atual.contains("versão anterior do prompt"); "$atual, agora com mais detalhes de composição e iluminação de estúdio profissional." }
        val objetivoResolvido = "Objetivo atual: melhore esse prompt\n" +
            "Referências resolvidas:\n- artefato anterior: versão anterior do prompt gerado para o pedido"
        val execution = executor(library, iaFuncional).execute(request(objetivoResolvido), capability, decision)

        assertTrue(execution.success)
        assertTrue(recebeuPromptAnterior)
        assertTrue(execution.result.orEmpty().contains("Melhorei"))
    }
}
