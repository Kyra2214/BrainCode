package com.brain.planner

import com.brain.execution.RiskClass
import com.brain.router.PapelPipeline

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
        val pedidoDePrompt = normalizado.containsAny("prompt", "template de prompt")
        if (normalizado.containsAny(
                "pesquis", "analis", "investig", "compar", "encontr", "document",
                "mais atual", "mais recentes", "mudanças recentes", "técnicas atuais"
            )
        ) {
            passos += PassoPlano(
                "pesquisar", "network.research", "evidência de pesquisa disponível",
                papel = PapelPipeline.PLANEJAMENTO, riskClass = RiskClass.MEDIUM
            )
        }
        if (pedidoDePrompt || normalizado.containsAny("escrev", "cri", "ger", "document", "relatóri", "relatori", "desenvolv", "constru", "mont", "aplicat", "aplicativo", "sistem", "site", "software")) {
            passos += PassoPlano(
                "produzir", if (pedidoDePrompt) "prompt.library.write" else "workspace.write", "artefato produzido",
                parametros = listOf(texto),
                dependeDe = passos.map { it.id }, papel = if (pedidoDePrompt) PapelPipeline.ESCRITA_DE_PROMPT else PapelPipeline.PRODUCAO_DE_ARTEFATO,
                riskClass = RiskClass.MEDIUM
            )
        }
        if (normalizado.containsAny("códig", "codig", "program", "implement", "compil", "test")) {
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

    private fun String.containsAny(vararg termos: String) = termos.any { it in this }
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
}
