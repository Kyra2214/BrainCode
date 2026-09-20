package com.brain.planner

import com.brain.execution.RiskClass
import com.brain.prompt.PromptDomain
import com.brain.reasoning.ReasoningState
import com.brain.router.PapelPipeline
import com.brain.secretary.DeterministicSecretary
import com.brain.secretary.DoorPolicy
import com.brain.secretary.OrderIntent
import com.brain.text.IntentNegation

/** Decompõe objetivo em funções declarativas; não autoriza nem executa. */
fun interface FunctionSplitter {
    fun split(objetivo: String): List<PassoPlano>
}

/** Splitter determinístico consolidado da lógica que já existia no KeywordPlanner. */
class KeywordFunctionSplitter : FunctionSplitter {
    override fun split(objetivo: String): List<PassoPlano> {
        val texto = objetivo.trim()
        require(texto.isNotBlank()) { "objetivo não pode ser vazio" }
        val normalizado = texto.lowercase()
        val passos = mutableListOf<PassoPlano>()
        val pedidoLiteralDePrompt = IntentNegation.hasAllowedOccurrence(normalizado, "prompt", "template de prompt")
        // Pedidos de transformação de foto frequentemente dizem apenas o que a IA deve
        // fazer ("transforme minha imagem", "vou enviar uma foto"), sem usar a palavra
        // "prompt". Eles ainda precisam cair no Prompt Creator, não no fallback de análise
        // local que pode executar sandbox.info.
        val pedidoVisualDePrompt = PromptDomain.classificar(normalizado) == PromptDomain.IMAGEM &&
            IntentNegation.hasAllowedOccurrence(normalizado, "transform", "alter", "modific", "edita", "conver", "recri", "aplic")
        val pedidoDePrompt = pedidoLiteralDePrompt || pedidoVisualDePrompt
        val pesquisaExplicita = IntentNegation.hasAllowedOccurrence(normalizado,
                "pesquis", "analis", "investig", "compar", "encontr", "document",
                "mais atual", "mais recentes", "mudanças recentes", "técnicas atuais"
            )
        // Prompts visuais se beneficiam de referências técnicas mesmo quando o usuário
        // não escreve literalmente "pesquise"; prompts de arquitetura/texto não devem
        // ganhar uma etapa de rede apenas por conter a palavra "prompt".
        val promptVisual = pedidoDePrompt && PromptDomain.classificar(normalizado) == PromptDomain.IMAGEM
        val pesquisaNecessaria = pesquisaExplicita || promptVisual
        if (!normalizado.contains("criar documento") && pesquisaNecessaria) {
            passos += PassoPlano(
                "pesquisar", "network.research", "evidência de pesquisa disponível",
                parametros = listOf(
                    if (promptVisual) ResearchQuery.paraPromptVisual(texto)
                    else texto.substringBefore("\nReferências resolvidas:").trim().ifBlank { texto }
                ),
                papel = PapelPipeline.PLANEJAMENTO, riskClass = RiskClass.MEDIUM
            )
        }
        if (pedidoDePrompt || IntentNegation.hasAllowedOccurrence(normalizado, "escrev", "cri", "ger", "produz", "document", "relatóri", "relatori", "desenvolv", "constru", "mont", "aplicat", "aplicativo", "sistem", "site", "software")) {
            passos += PassoPlano(
                "produzir", if (pedidoDePrompt) "prompt.library.write" else "workspace.write", "artefato produzido",
                parametros = listOf(texto),
                dependeDe = passos.map { it.id }, papel = if (pedidoDePrompt) PapelPipeline.ESCRITA_DE_PROMPT else PapelPipeline.PRODUCAO_DE_ARTEFATO,
                riskClass = RiskClass.MEDIUM
            )
        }
        if (!pedidoDePrompt && IntentNegation.hasAllowedOccurrence(normalizado, "códig", "codig", "program", "implement", "compil", "test", "execut")) {
            passos += PassoPlano(
                "executar", "sandbox.code", "execução e testes concluídos",
                dependeDe = passos.map { it.id }, papel = PapelPipeline.EXECUCAO_CODIGO,
                riskClass = RiskClass.LOW
            )
        }
        if (passos.isEmpty()) {
            passos += PassoPlano("entender", "brain.analyze", "objetivo classificado", papel = PapelPipeline.PLANEJAMENTO)
        }
        return passos
    }
}

/**
 * Splitter que aplica a intenção do Secretário antes de entregar o plano ao
 * PolicyBroker. O splitter legado continua sendo usado quando nenhuma intenção
 * é fornecida, preservando as chamadas existentes.
 */
class DoorAwareSplitter(
    private val secretary: DeterministicSecretary = DeterministicSecretary(),
    private val legacy: FunctionSplitter = KeywordFunctionSplitter()
) : FunctionSplitter {
    override fun split(objetivo: String): List<PassoPlano> = split(secretary.classify(objetivo), objetivo)

    fun split(intent: OrderIntent, objetivo: String = intent.originalPrompt): List<PassoPlano> {
        if (intent.door == com.brain.secretary.Door.CHAT) {
            val legacyCandidates = legacy.split(objetivo)
            val research = legacyCandidates.firstOrNull { it.capacidade == "network.research" }
                ?.takeIf { DoorPolicy.allows(intent.scope, it.capacidade) }
            val response = PassoPlano(
                id = "responder",
                capacidade = "chat.respond",
                criterioSucesso = "resposta conversacional não vazia",
                parametros = listOf(objetivo),
                dependeDe = listOfNotNull(research?.id),
                papel = PapelPipeline.PLANEJAMENTO,
                riskClass = RiskClass.LOW
            )
            return listOfNotNull(research, response)
        }
        val candidates = legacy.split(objetivo)
        val allowed = candidates.filter { DoorPolicy.allows(intent.scope, it.capacidade) }
        if (allowed.isEmpty()) return listOf(
            PassoPlano("entender", "brain.analyze", "objetivo classificado", papel = PapelPipeline.PLANEJAMENTO)
        )
        val ids = allowed.map { it.id }.toSet()
        return allowed.map { passo -> passo.copy(dependeDe = passo.dependeDe.filter { it in ids }) }
    }
}

/** Planner canônico: recebe funções divididas e apenas monta o ExecutionPlan. */
class KeywordPlanner(
    private val splitter: FunctionSplitter = KeywordFunctionSplitter()
) : Planner {
    override suspend fun planejar(objetivo: String): PlanoExecucao {
        val texto = objetivo.trim()
        require(texto.isNotBlank()) { "objetivo não pode ser vazio" }
        return PlanoExecucao(texto, splitter.split(texto))
    }

    suspend fun planejar(objetivo: String, intent: OrderIntent, reasoning: ReasoningState? = null): PlanoExecucao {
        val texto = objetivo.trim()
        require(texto.isNotBlank()) { "objetivo não pode ser vazio" }
        val intentSplitter = splitter as? DoorAwareSplitter ?: DoorAwareSplitter()
        val base = PlanoExecucao(texto, intentSplitter.split(intent, texto))
        return if (reasoning == null) base else base.copy(
            assumptions = reasoning.assumptions.toSet(),
            fallback = if (reasoning.missing.isEmpty()) null else "prosseguir-localmente-com-suposições-explicitas",
            missingRequirements = reasoning.missing,
            contextPack = reasoning.contextPack
        )
    }

    override suspend fun planejar(objetivo: String, reasoning: ReasoningState): PlanoExecucao =
        planejar(objetivo).copy(
            assumptions = reasoning.assumptions.toSet(),
            fallback = if (reasoning.missing.isEmpty()) null else "prosseguir-localmente-com-suposições-explicitas",
            missingRequirements = reasoning.missing,
            contextPack = reasoning.contextPack
        )
}
