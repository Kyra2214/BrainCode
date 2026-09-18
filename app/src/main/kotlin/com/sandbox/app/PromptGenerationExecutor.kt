package com.sandbox.app

import com.brain.capability.CapabilityDefinition
import com.brain.gateway.ActionExecution
import com.brain.gateway.ActionExecutor
import com.brain.gateway.ActionRequest
import com.brain.policy.PolicyDecision
import com.brain.prompt.LocalPromptCreatorAgent
import com.brain.prompt.PromptCreatorAgent
import com.brain.prompt.PromptCriado
import com.brain.prompt.PromptLibrary
import com.brain.prompt.PromptOutcomeTracker
import com.brain.prompt.PromptOutcomeTrackers
import com.brain.prompt.PromptQualityScore
import com.brain.prompt.PromptQualityValidator
import com.brain.prompt.PromptSimilarity
import com.brain.prompt.PromptTemplate
import com.brain.prompt.taxaSucessoEfetiva
import com.brain.reasoning.ReasoningEngine
import com.brain.reasoning.RevisionEngine
import com.brain.router.PapelPipeline
import com.brain.capability.CostClass
import kotlinx.coroutines.runBlocking
import java.util.Locale

/** Encaminha um pedido de melhoria de prompt para uma IA — só chamado quando a qualidade local é insuficiente. */
fun interface PromptImprover {
    /** @return texto melhorado, ou lança se nenhuma IA estiver disponível/responder. */
    fun melhorar(promptAtual: String, pedidoOriginal: String): String

    fun melhorar(promptAtual: String, pedidoOriginal: String, pontosFracos: Set<String>): String =
        melhorar(promptAtual, pedidoOriginal)

    fun melhorar(promptAtual: String, pedidoOriginal: String, pontosFracos: Set<String>, authorizedAccountIds: Set<String>): String =
        melhorar(promptAtual, pedidoOriginal, pontosFracos)

    /**
     * Tier de custo real da última chamada a [melhorar]. Default FREE para implementações
     * (fakes de teste, por exemplo) que não sabem/não têm custo real a reportar.
     */
    fun custoDaUltimaMelhoria(): CostClass = CostClass.FREE
}

/** Implementação padrão: delega ao BrainApiGateway (mesmo caminho de IA já usado pelo resto do app). */
class GatewayPromptImprover(private val gateway: BrainApiGateway) : PromptImprover {
    @Volatile private var ultimoCusto: CostClass = CostClass.FREE

    override fun melhorar(promptAtual: String, pedidoOriginal: String): String =
        melhorar(promptAtual, pedidoOriginal, emptySet())

    override fun melhorar(promptAtual: String, pedidoOriginal: String, pontosFracos: Set<String>): String {
        return melhorar(promptAtual, pedidoOriginal, pontosFracos, emptySet())
    }

    override fun melhorar(
        promptAtual: String,
        pedidoOriginal: String,
        pontosFracos: Set<String>,
        authorizedAccountIds: Set<String>
    ): String {
        val specialistPrompt = """
            Você é o especialista de engenharia de prompts do BrainCode.
            Reescreva somente o prompt existente, preservando rigorosamente a intenção original.
            Não mude o assunto, não invente requisitos e não adicione explicações.
            A validação determinística encontrou estes pontos fracos: ${pontosFracos.ifEmpty { setOf("qualidade geral") }.joinToString(", ")}.
            Corrija esses pontos sem alterar o objetivo. Responda somente com o prompt final,
            sem aspas, prefácio, conclusão ou comentários.

            Pedido original:
            $pedidoOriginal

            Prompt atual (a melhorar):
            $promptAtual
        """.trimIndent()
        val resultado = gateway.complete(specialistPrompt, PapelPipeline.ESCRITA_DE_PROMPT, authorizedAccountIds)
        ultimoCusto = resultado.costClass
        return resultado.text.trim()
    }

    override fun custoDaUltimaMelhoria(): CostClass = ultimoCusto
}

/** Conversão determinística de tier qualitativo para um número comparável/agregável na biblioteca. */
private fun CostClass.paraCustoNumerico(): Double = when (this) {
    CostClass.FREE -> 0.0
    CostClass.LOW -> 0.05
    CostClass.MEDIUM -> 0.15
    CostClass.HIGH -> 0.40
    CostClass.UNKNOWN -> 0.10
}

/**
 * Fluxo: biblioteca (referência) -> Prompt Creator local (sempre funciona) -> Validator
 * -> se insuficiente, IA como escalonamento OPCIONAL (nunca obrigatória) -> validação final.
 *
 * A ausência de API nunca impede a entrega de um prompt: nesse caso o melhor resultado
 * local é entregue, com uma nota informando que a melhoria por IA não estava disponível.
 */
class PromptGenerationExecutor(
    private val promptLibrary: PromptLibrary,
    private val improver: PromptImprover,
    private val outcomeTracker: PromptOutcomeTracker = PromptOutcomeTrackers.forLibrary(promptLibrary),
    private val creator: PromptCreatorAgent = LocalPromptCreatorAgent(),
    private val reasoningEngine: ReasoningEngine = ReasoningEngine(),
    private val revisionEngine: RevisionEngine = RevisionEngine(creator = creator)
) : ActionExecutor {

    override fun execute(request: ActionRequest, capability: CapabilityDefinition, decision: PolicyDecision): ActionExecution {
        val startedAt = System.nanoTime()
        val objetivoBruto = request.parameters["parameter.0"]?.trim().orEmpty()
        if (objetivoBruto.isBlank()) return ActionExecution(false, error = "objetivo do prompt ausente", provenance = provenance(capability))

        val contextoPesquisa = extrairContextoPesquisa(request)
        val pedidoDeMelhoria = extrairPedidoDeMelhoria(objetivoBruto)
        val reasoning = reasoningEngine.analyze(objetivoBruto)

        val evidenciasBase = mutableListOf<String>()
        evidenciasBase += "reasoning:intent=${reasoning.intent.name.lowercase(Locale.ROOT)}"
        if (contextoPesquisa != null) evidenciasBase += "web-research:contexto-considerado"

        // 1) Pedido explícito de melhoria de um prompt já existente — vai direto para o ciclo de melhoria.
        if (pedidoDeMelhoria != null) {
            return processarMelhoriaExplicita(pedidoDeMelhoria, contextoPesquisa, capability, evidenciasBase, startedAt, request.actionId, decision.authorizedAccountIds)
        }

        val objetivo = objetivoBruto

        // 2) Biblioteca como referência/contexto — nunca como bloqueio.
        val candidato = runBlocking {
            promptLibrary.buscarPorContexto(objetivo)
                .asSequence()
                .map { it to PromptSimilarity.compatibility(objetivo, it) }
                .filter { (it, score) -> score >= PromptSimilarity.LIMIAR_COMPATIBILIDADE_REUSO && it.taxaSucessoEfetiva() >= TAXA_SUCESSO_MINIMA_PARA_REUSO }
                .maxWithOrNull(
                    compareBy<Pair<PromptTemplate, Double>> { it.second }
                        .thenBy { it.first.taxaSucessoEfetiva() }
                        .thenBy { it.first.amostrasObservadas }
                )
                ?.first
        }

        val criado = creator.criar(objetivo, candidato, contextoPesquisa)
        return finalizar(objetivo, criado, candidato, contextoPesquisa, evidenciasBase, capability, startedAt, request.actionId, decision.authorizedAccountIds)
    }

    /** "melhore esse prompt" / "otimize" / "deixe mais profissional" — escala direto, sem passar pela biblioteca. */
    private fun processarMelhoriaExplicita(
        pedido: PedidoDeMelhoria,
        contextoPesquisa: String?,
        capability: CapabilityDefinition,
        evidenciasBase: List<String>,
        startedAt: Long,
        actionId: String,
        authorizedAccountIds: Set<String>
    ): ActionExecution {
        val scoreInicial = PromptQualityValidator.validar(pedido.instrucao, pedido.promptAnterior, com.brain.prompt.PromptDomain.classificar(pedido.instrucao))
        val (textoFinal, origem, scoreFinal, aiUsada) = escalonar(
            pedido.instrucao,
            PromptCriado(pedido.promptAnterior, com.brain.prompt.PromptDomain.classificar(pedido.instrucao), "usuario:prompt-anterior"),
            scoreInicial,
            contextoPesquisa,
            authorizedAccountIds
        )
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        val prefixo = if (aiUsada) "Melhorei o prompt com apoio de IA especialista" else "Melhorei o prompt com o Prompt Creator local"
        outcomeTracker.markUsed(actionId, saveGeneratedPrompt(pedido.instrucao, textoFinal))
        return ActionExecution(
            success = true,
            result = "$prefixo (qualidade ${(scoreFinal.total * 100).toInt()}%):\n\n$textoFinal",
            evidence = evidenciasBase + listOfNotNull(
                "prompt-creator:origem:$origem",
                "prompt-quality:total:${"%.2f".format(scoreFinal.total)}",
                "prompt-generation-latency-ms:$elapsedMs",
                if (aiUsada) "prompt-generation:custo-tier:${improver.custoDaUltimaMelhoria()}" else null
            ),
            provenance = provenance(capability),
            custo = if (aiUsada) improver.custoDaUltimaMelhoria().paraCustoNumerico() else 0.0
        )
    }

    private fun finalizar(
        objetivo: String,
        criado: PromptCriado,
        candidatoBiblioteca: PromptTemplate?,
        contextoPesquisa: String?,
        evidenciasBase: List<String>,
        capability: CapabilityDefinition,
        startedAt: Long,
        actionId: String,
        authorizedAccountIds: Set<String>
    ): ActionExecution {
        val scoreInicial = PromptQualityValidator.validar(objetivo, criado.texto, criado.dominio)
        val (textoEscalonado, origem, scoreFinal, aiUsada) = escalonar(objetivo, criado, scoreInicial, contextoPesquisa, authorizedAccountIds)
        val textoFinal = textoEscalonado
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        val savedId = saveGeneratedPrompt(objetivo, textoFinal)
        outcomeTracker.markUsed(actionId, savedId)

        val prefixo = when {
            candidatoBiblioteca != null -> "Encontrei um prompt de referência na biblioteca e adaptei ao seu pedido"
            aiUsada -> "Não encontrei prompt compatível na biblioteca. Criei um prompt localmente e refinei com IA especialista"
            else -> "Não encontrei prompt compatível na biblioteca. Criei um prompt novo localmente"
        }
        val notaIa = if (!aiUsada && scoreFinal.abaixoDoPadrao) "\n\n(Melhoria por IA não está disponível no momento — entreguei o melhor resultado local possível.)" else ""

        return ActionExecution(
            success = true,
            result = "$prefixo (qualidade ${(scoreFinal.total * 100).toInt()}%):\n\n$textoFinal$notaIa",
            evidence = evidenciasBase + listOfNotNull(
                candidatoBiblioteca?.let { "prompt-library:${it.id}" },
                "prompt-creator:origem:$origem",
                "prompt-quality:total:${"%.2f".format(scoreFinal.total)}",
                "prompt-library:saved-before-response",
                "prompt-library:id:$savedId",
                "prompt-generation-latency-ms:$elapsedMs",
                if (aiUsada) "prompt-generation:custo-tier:${improver.custoDaUltimaMelhoria()}" else null
            ),
            provenance = provenance(capability),
            custo = if (aiUsada) improver.custoDaUltimaMelhoria().paraCustoNumerico() else 0.0
        )
    }

    /** Validator -> se insuficiente, tenta IA; se IA falhar/indisponível, melhoria heurística local. Nunca lança. */
    private fun escalonar(
        pedido: String,
        criado: PromptCriado,
        scoreInicial: PromptQualityScore,
        contextoPesquisa: String?,
        authorizedAccountIds: Set<String>
    ): EscalonamentoResultado {
        if (!scoreInicial.abaixoDoPadrao) return EscalonamentoResultado(criado.texto, criado.origem, scoreInicial, false)

        val viaIa = runCatching { improver.melhorar(criado.texto, pedido, scoreInicial.pontosFracos, authorizedAccountIds) }.getOrNull()?.takeIf { it.isNotBlank() }
        if (viaIa != null) {
            val scoreIa = PromptQualityValidator.validar(pedido, viaIa, criado.dominio)
            if (scoreIa.total >= scoreInicial.total) return EscalonamentoResultado(viaIa, "${criado.origem}+ia-especialista", scoreIa, true)
        }

        val reasoning = reasoningEngine.analyze(pedido)
        val revisao = revisionEngine.revise(reasoning, criado.texto)
        val scoreLocal = revisao.critique.score
        return if (scoreLocal.total >= scoreInicial.total) EscalonamentoResultado(revisao.prompt, "local:revision-engine:${revisao.revisions}", scoreLocal, false)
        else EscalonamentoResultado(criado.texto, criado.origem, scoreInicial, false)
    }

    private data class EscalonamentoResultado(val texto: String, val origem: String, val score: PromptQualityScore, val aiUsada: Boolean)

    private data class PedidoDeMelhoria(val instrucao: String, val promptAnterior: String)

    /**
     * O ConversationContextEngine (SandboxViewModel) já detecta "melhore/otimize/deixe mais
     * profissional/faça uma versão melhor" e resolve a referência ("ele"/"esse prompt") para o
     * artefato anterior da conversa, montando o objetivo no formato:
     * "Objetivo atual: <instrução>\n...\nReferências resolvidas:\n- artefato anterior: <texto>".
     * Aqui só extraímos as duas partes — nenhuma segunda heurística de histórico é criada.
     */
    private fun extrairPedidoDeMelhoria(objetivo: String): PedidoDeMelhoria? {
        val marcadorArtefato = "artefato anterior: "
        val idxArtefato = objetivo.indexOf(marcadorArtefato)
        if (idxArtefato < 0) return null
        if (PALAVRAS_MELHORIA.none { it in objetivo.lowercase(Locale.ROOT) }) return null
        val instrucao = if (objetivo.startsWith("Objetivo atual: ")) {
            objetivo.removePrefix("Objetivo atual: ").substringBefore("\n").trim()
        } else objetivo.substringBefore("\n").trim()
        val anterior = objetivo.substring(idxArtefato + marcadorArtefato.length).trim()
        if (instrucao.isBlank() || anterior.isBlank()) return null
        return PedidoDeMelhoria(instrucao, anterior)
    }

    private fun extrairContextoPesquisa(request: ActionRequest): String? {
        val extras = request.parameters.entries
            .filter { it.key.startsWith("parameter.") && it.key != "parameter.0" }
            .sortedBy { it.key }
            .joinToString("\n") { it.value }
            .trim()
        val bruto = if (extras.isNotBlank()) extras else request.parameters.entries
            .filter { it.key != "parameter.0" }
            .sortedBy { it.key }
            .joinToString("\n") { it.value }
            .trim()
        if (bruto.isBlank()) return null
        if (bruto.startsWith("WebResearch indisponível", ignoreCase = true)) return null
        return bruto
    }

    /** Propõe um id estável a partir do objetivo, mas quem decide se isso é duplicata de um
     *  template já existente (e portanto deve herdar id/estatísticas antigas) é só a biblioteca —
     *  ver [PromptLibrary.salvarNovaVersao]. Nenhuma checagem de duplicata é feita aqui. */
    private fun saveGeneratedPrompt(objective: String, generated: String): String = runBlocking {
        promptLibrary.salvarNovaVersao(
            PromptTemplate(
                id = "generated-${stableId(objective)}",
                versao = 1,
                finalidade = "geração e melhoria de prompt",
                contextoDeUso = objective,
                skillRelacionada = "prompt-generation",
                agenteRelacionado = "prompt-creator-agent",
                textoTemplate = generated,
                taxaSucesso = 0.5,
                custoMedio = 0.0,
                tempoMedioMs = 0L,
                historicoMelhorias = listOf("gerado/melhorado pelo Prompt Creator local e salvo antes da resposta"),
                amostrasObservadas = 0
            )
        )
    }

    private fun stableId(objective: String): String = Integer.toUnsignedString(objective.lowercase(Locale.ROOT).hashCode(), 36)
    private fun provenance(capability: CapabilityDefinition) = listOf("app:PromptGenerationExecutor", "capability:${capability.id}")

    private companion object {
        const val TAXA_SUCESSO_MINIMA_PARA_REUSO = 0.5
        val PALAVRAS_MELHORIA = listOf("melhor", "otimiz", "mais profissional", "versão melhor", "refaç", "reformul", "fundo", "deserto", "meteoro", "adicione", "adiciona", "mude", "muda", "troque", "troca", "substitua", "substitui")
    }
}
