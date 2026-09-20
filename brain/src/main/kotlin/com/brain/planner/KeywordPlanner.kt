package com.brain.planner

import com.brain.execution.RiskClass
import com.brain.prompt.PromptDomain
import com.brain.reasoning.ReasoningState
import com.brain.router.PapelPipeline
import com.brain.text.TermMatcher

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
        val pedidoLiteralDePrompt = TermMatcher.containsAnyWhole(normalizado, "prompt", "template de prompt")
        // Pedidos de transformação de foto frequentemente dizem apenas o que a IA deve
        // fazer ("transforme minha imagem", "vou enviar uma foto"), sem usar a palavra
        // "prompt". Eles ainda precisam cair no Prompt Creator, não no fallback de análise
        // local que pode executar sandbox.info.
        val pedidoVisualDePrompt = PromptDomain.classificar(normalizado) == PromptDomain.IMAGEM &&
            TermMatcher.containsAnyStem(normalizado, "transform", "alter", "modific", "edita", "conver", "recri", "aplic")
        val pedidoDePrompt = pedidoLiteralDePrompt || pedidoVisualDePrompt
        val pesquisaExplicita = TermMatcher.containsAnyStem(normalizado,
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
                // A consulta usa só o que o usuário pediu agora; o texto do artefato anterior
                // ("Referências resolvidas") só atrapalha a busca.
                parametros = listOf(texto.substringBefore("\nReferências resolvidas:").trim().ifBlank { texto }),
                papel = PapelPipeline.PLANEJAMENTO, riskClass = RiskClass.MEDIUM
            )
        }
        if (pedidoDePrompt || TermMatcher.containsAnyStem(normalizado, "escrev", "cri", "ger", "document", "relatóri", "relatori", "desenvolv", "constru", "mont", "aplicat", "aplicativo", "sistem", "site", "software")) {
            passos += PassoPlano(
                "produzir", if (pedidoDePrompt) "prompt.library.write" else "workspace.write", "artefato produzido",
                parametros = listOf(texto),
                dependeDe = passos.map { it.id }, papel = if (pedidoDePrompt) PapelPipeline.ESCRITA_DE_PROMPT else PapelPipeline.PRODUCAO_DE_ARTEFATO,
                riskClass = RiskClass.MEDIUM
            )
        }
        if (!pedidoDePrompt && TermMatcher.containsAnyStem(normalizado, "códig", "codig", "program", "implement", "compil", "test")) {
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

/** Planner canônico: recebe funções divididas e apenas monta o ExecutionPlan. */
class KeywordPlanner(
    private val splitter: FunctionSplitter = KeywordFunctionSplitter()
) : Planner {
    override suspend fun planejar(objetivo: String): PlanoExecucao {
        val texto = objetivo.trim()
        require(texto.isNotBlank()) { "objetivo não pode ser vazio" }
        return PlanoExecucao(texto, splitter.split(texto))
    }

    override suspend fun planejar(objetivo: String, reasoning: ReasoningState): PlanoExecucao =
        planejar(objetivo).copy(
            assumptions = reasoning.assumptions.toSet(),
            fallback = if (reasoning.missing.isEmpty()) null else "prosseguir-localmente-com-suposições-explicitas",
            missingRequirements = reasoning.missing,
            contextPack = reasoning.contextPack
        )
}
