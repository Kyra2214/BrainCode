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
import com.brain.prompt.PromptCreatorAgent
import com.brain.prompt.PromptCriado
import com.brain.prompt.PromptDomain
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

    private fun executor(library: PromptLibrary, improver: PromptImprover, creator: PromptCreatorAgent? = null) = PromptGenerationExecutor(
        promptLibrary = library,
        improver = improver,
        creator = creator ?: com.brain.prompt.LocalPromptCreatorAgent()
    )

    private val criadorFraco = object : PromptCreatorAgent {
        override fun criar(pedido: String, contexto: PromptTemplate?, contextoPesquisa: String?): PromptCriado =
            PromptCriado("xícara", PromptDomain.IMAGEM, "test:fraco")
        override fun melhorarLocalmente(promptAtual: String, pedidoOriginal: String, pontosFracos: Set<String>, contextoPesquisa: String?): PromptCriado =
            PromptCriado(promptAtual, PromptDomain.IMAGEM, "test:fraco")
    }

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
            "Fotografia profissional detalhada de uma xícara de café: $atual Composição cuidadosamente balanceada, iluminação de estúdio " +
                "de três pontos, lente 85mm, profundidade de campo rasa, altíssima definição e riqueza de detalhes realistas."
        }
        val execution = executor(library, iaFuncional, criadorFraco).execute(request("crie um prompt de uma xícara de café"), capability, decision)

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
                "Fotografia profissional detalhada de uma xícara de café: $promptAtual Composição cuidadosamente balanceada, iluminação de estúdio " +
                    "de três pontos, lente 85mm, profundidade de campo rasa, altíssima definição e riqueza de detalhes realistas."
            override fun custoDaUltimaMelhoria(): CostClass = CostClass.MEDIUM
        }
        val execution = executor(library, iaPaga, criadorFraco).execute(request("crie um prompt de uma xícara de café"), capability, decision)

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
        val execution = executor(library, iaFuncional, criadorFraco).execute(request(objetivoResolvido), capability, decision)

        assertTrue(execution.success)
        assertTrue(recebeuPromptAnterior)
        assertTrue(execution.result.orEmpty().contains("Melhorei"))
    }

    @Test fun `melhoria do foguete usa detalhes novos e o artefato anterior`() {
        val library = FakePromptLibrary()
        var instrucaoRecebida = ""
        var promptAnteriorRecebido = ""
        val iaFuncional = PromptImprover { atual, original ->
            instrucaoRecebida = original
            promptAnteriorRecebido = atual
            "$atual, no deserto ao entardecer, com uma plataforma ao longe"
        }
        val objetivoResolvido = "Objetivo atual: vamos melhorar quero esse foguete no deserto ao entardecer a imagem e de uma plataforma ao longe\n" +
            "Referências resolvidas:\n- artefato anterior: Fotografia fotorrealista de um foguete espacial decolando, com iluminação cinematográfica"

        val execution = executor(library, iaFuncional, criadorFraco).execute(request(objetivoResolvido), capability, decision)

        assertTrue(execution.success)
        assertTrue(execution.result.orEmpty().contains("Melhorei"))
        assertTrue(instrucaoRecebida.contains("deserto"))
        assertTrue(instrucaoRecebida.contains("entardecer"))
        assertTrue(instrucaoRecebida.contains("plataforma ao longe"))
        assertTrue(promptAnteriorRecebido.contains("foguete espacial decolando"))
    }

    @Test fun `melhoria sem referencia pede esclarecimento e nao usa instrucao como sujeito`() {
        val execution = executor(FakePromptLibrary(), iaIndisponivel).execute(
            request("vamos melhorar o prompt quero que o rosto da imagem seja igual sem mudar a fisionomia"),
            capability,
            decision
        )

        assertTrue(execution.success)
        assertTrue(execution.result.orEmpty().contains("prompt ou a imagem anterior"))
        assertTrue(execution.evidence.contains("prompt-checklist:aguardando-esclarecimento"))
    }

    // Regressão do print: follow-up com o artefato anterior embrulhado ("Encontrei um prompt... 81%"),
    // sem IA disponível. Antes o executor devolvia o prompt antigo, idêntico -> revision.no-progress.
    @Test fun `melhoria sem IA aplica fotorrealista deserto e visao de longe e nao devolve o prompt antigo`() {
        val anterior = "Ilustração digital detalhada de um foguete decolando, ambientado em um cenário coerente com o assunto, " +
            "sem elementos que não foram pedidos. Composição: enquadramento equilibrado, assunto em destaque no terço de composição. " +
            "Iluminação: iluminação natural e equilibrada realçando volumes e texturas. " +
            "Nível de realismo: nível de realismo fotográfico. Qualidade: alta definição, sem artefatos visuais."
        val objetivoResolvido = "Objetivo atual: muda para fotorrealista no deserto com visão de uma plataforma de longe\n" +
            "Referências resolvidas:\n- artefato anterior: Encontrei um prompt de referência na biblioteca e adaptei ao seu pedido " +
            "(estimativa heurística interna — qualidade 81%):\n\n$anterior"

        val execution = executor(FakePromptLibrary(), iaIndisponivel).execute(request(objetivoResolvido), capability, decision)
        val resultado = execution.result.orEmpty()

        assertTrue(execution.success)
        assertTrue(resultado.lowercase().contains("fotorrealista"))
        assertTrue(resultado.lowercase().contains("deserto"))
        assertTrue(resultado.lowercase().contains("visão de uma plataforma de longe"))
        assertFalse("o invólucro do turno anterior não pode ser reaproveitado como prompt",
            resultado.substringAfter("):").contains("Encontrei um prompt de referência"))
    }

    @Test fun `melhoria sem IA e sem regra local diz que nao aplicou em vez de fingir melhoria`() {
        val objetivoResolvido = "Objetivo atual: melhore esse prompt\n" +
            "Referências resolvidas:\n- artefato anterior: versão anterior do prompt gerado para o pedido"
        val execution = executor(FakePromptLibrary(), iaIndisponivel, criadorFraco).execute(request(objetivoResolvido), capability, decision)

        assertTrue(execution.success)
        assertTrue(execution.result.orEmpty().contains("mantive o prompt anterior"))
    }

    @Test fun `frase real do usuario sem IA entrega deserto por do sol e visao de longe`() {
        val anterior = "Ilustração digital detalhada de um foguete decolando, ambientado em um cenário coerente com o assunto, " +
            "sem elementos que não foram pedidos. Composição: enquadramento equilibrado, assunto em destaque no terço de composição. " +
            "Iluminação: iluminação natural e equilibrada realçando volumes e texturas. " +
            "Nível de realismo: nível de realismo fotográfico. Qualidade: alta definição, sem artefatos visuais."
        val objetivoResolvido = "Objetivo atual: vamos melhorar ele quero ele num deserto ao por do sol com a visão de uma plataforma de longe\n" +
            "Referências resolvidas:\n- artefato anterior: Encontrei um prompt de referência na biblioteca e adaptei ao seu pedido " +
            "(estimativa heurística interna — qualidade 81%):\n\n$anterior"

        val execution = executor(FakePromptLibrary(), iaIndisponivel).execute(request(objetivoResolvido), capability, decision)
        val resultado = execution.result.orEmpty().lowercase()

        assertTrue(execution.success)
        assertTrue(resultado.contains("deserto"))
        assertTrue(resultado.contains("pôr do sol"))
        assertTrue(resultado.contains("visão de uma plataforma de longe"))
        assertFalse(resultado.substringAfter("):").contains("encontrei um prompt de referência"))
    }

    private val promptAnteriorFoguete = "Ilustração digital detalhada de um foguete decolando, ambientado em um cenário coerente com o assunto, " +
        "sem elementos que não foram pedidos. Composição: enquadramento equilibrado, assunto em destaque no terço de composição. " +
        "Iluminação: iluminação natural e equilibrada realçando volumes e texturas. Qualidade: alta definição, sem artefatos visuais."

    @Test fun `gatilho melhore ele aciona a IA mesmo com regras locais aplicaveis`() {
        var chamadas = 0
        var recebeu = ""
        val ia = PromptImprover { atual, _ -> chamadas++; recebeu = atual; "$atual Detalhes extras de textura e atmosfera de deserto ao pôr do sol." }
        val objetivo = "Objetivo atual: melhore ele quero ele num deserto ao por do sol com a visão de uma plataforma de longe\n" +
            "Referências resolvidas:\n- artefato anterior: $promptAnteriorFoguete"

        val execution = executor(FakePromptLibrary(), ia).execute(request(objetivo), capability, decision)

        assertTrue(execution.success)
        assertEquals(1, chamadas)
        assertTrue("a IA deve receber o prompt já ajustado pelas regras locais", recebeu.contains("ambientado em deserto"))
        assertTrue(execution.result.orEmpty().contains("apoio de IA especialista"))
        assertTrue(execution.evidence.contains("prompt-improvement:gatilho-ia"))
        assertTrue(execution.result.orEmpty().lowercase().contains("visão de uma plataforma de longe"))
    }

    @Test fun `gatilho sem IA disponivel entrega o resultado local sem aviso no texto`() {
        val objetivo = "Objetivo atual: refaça ele no deserto ao por do sol\n" +
            "Referências resolvidas:\n- artefato anterior: $promptAnteriorFoguete"

        val execution = executor(FakePromptLibrary(), iaIndisponivel).execute(request(objetivo), capability, decision)
        val resultado = execution.result.orEmpty()

        assertTrue(execution.success)
        assertFalse("IA pode estar desligada por escolha: sem aviso no texto", resultado.contains("não está disponível"))
        assertTrue(execution.evidence.contains("prompt-improvement:ia-indisponivel-entregue-local"))
        assertTrue(resultado.lowercase().contains("deserto"))
    }

    @Test fun `conta nao autorizada impede chamada da IA que exige autorizacao`() {
        var chamadas = 0
        val iaComConta = object : PromptImprover {
            override fun melhorar(promptAtual: String, pedidoOriginal: String): String { chamadas++; return promptAtual }
            override fun requerContaAutorizada(): Boolean = true
        }
        val objetivo = "Objetivo atual: melhore ele\nReferências resolvidas:\n- artefato anterior: $promptAnteriorFoguete"

        val execution = executor(FakePromptLibrary(), iaComConta).execute(request(objetivo), capability, decision)

        assertTrue(execution.success)
        assertEquals(0, chamadas)
    }

    // Regressão do bug corrigido em 23/09/2026 (ver
    // docs/auditoria/PLANO_CORRECAO_AUDITORIA_ESCALONAMENTO.md, item 1): um PRIMEIRO pedido, sem
    // nenhuma palavra de melhoria ("crie um prompt de X", não "melhore"), com qualidade local
    // insuficiente e uma conta autorizada disponível, deve de fato chamar a IA real — antes,
    // authorizedAccountIds chegava sempre vazio nesse caminho e a IA nunca era acionada, mesmo
    // com um PromptImprover que `requerContaAutorizada() = true` (o comportamento real do
    // GatewayPromptImprover, não reproduzido pelo SAM fake usado nos demais testes desta classe).
    @Test fun `primeira geracao sem gatilho de melhoria aciona IA que exige conta quando ha conta autorizada`() {
        var chamadas = 0
        val iaComContaExigida = object : PromptImprover {
            override fun melhorar(promptAtual: String, pedidoOriginal: String): String {
                chamadas++
                return "Fotografia profissional detalhada de uma xícara de café: $promptAtual Composição cuidadosamente balanceada, " +
                    "iluminação de estúdio de três pontos, lente 85mm, profundidade de campo rasa, altíssima definição e riqueza de detalhes realistas."
            }
            override fun requerContaAutorizada(): Boolean = true
        }
        val decisaoComContaAutorizada = decision.copy(authorizedAccountIds = setOf("acct-1"))

        val execution = executor(FakePromptLibrary(), iaComContaExigida, criadorFraco)
            .execute(request("crie um prompt de uma xícara de café"), capability, decisaoComContaAutorizada)

        assertTrue(execution.success)
        assertEquals("a IA deveria ter sido chamada nesse caminho", 1, chamadas)
        assertTrue("origem deveria indicar uso de IA especialista", execution.evidence.any { it.contains("ia-especialista") })
    }
}
