package com.brain.skill

import com.brain.text.TermMatcher

/**
 * Seleção 100% local (sem rede/API) de quais skills do catálogo RootFS 0.6 parecem relevantes
 * para um objetivo de criação (Porta 3). Reaproveita [TermMatcher], igual ao restante do projeto.
 *
 * As descrições das skills vêm em inglês (SKILL.md do RootFS 0.6); os objetivos do usuário vêm
 * majoritariamente em português. Por isso o casamento usa dois sinais, cada um suficiente sozinho:
 * 1) termos em português curados por skill (ver [GATILHOS_PT]);
 * 2) palavras da própria `description` da skill, para pedidos que já vierem em termos técnicos
 *    em inglês (ex.: "TDD", "refactor", "ADR").
 */
object RooftsSkillSelector {
    /** Máximo de skills injetadas no prompt de uma única chamada — orçamento de tokens. */
    const val MAX_SKILLS = 3

    fun select(objetivo: String, skills: List<RooftsSkill>, max: Int = MAX_SKILLS): List<RooftsSkill> {
        if (skills.isEmpty() || objetivo.isBlank()) return emptyList()
        val texto = objetivo.lowercase()
        val pontuadas = skills.mapNotNull { skill ->
            if (TermMatcher.containsAnyStem(texto, skill.exclusions)) return@mapNotNull null
            val score = pontuar(texto, skill)
            if (score > 0) skill to score else null
        }
        val selecionadas = pontuadas.sortedWith(
            compareByDescending<Pair<RooftsSkill, Int>> { it.second }.thenBy { it.first.id }
        ).take(max).map { it.first }
        // Nenhum sinal específico bateu: "planejar e quebrar em passos" é a base razoável para
        // qualquer geração de código, então entra como default de baixo custo em vez de nada.
        return selecionadas.ifEmpty { skills.filter { it.id == "planning-and-task-breakdown" } }
    }

    private fun pontuar(textoObjetivo: String, skill: RooftsSkill): Int {
        var pontos = 0
        val gatilhosPt = GATILHOS_PT[skill.id].orEmpty()
        if (gatilhosPt.isNotEmpty() && TermMatcher.containsAnyStem(textoObjetivo, gatilhosPt)) pontos += 2
        if (skill.triggers.isNotEmpty() && TermMatcher.containsAnyStem(textoObjetivo, skill.triggers)) pontos += 2
        val palavrasDescricao = descricaoComoTermos(skill.description)
        if (palavrasDescricao.isNotEmpty() && TermMatcher.containsAnyStem(textoObjetivo, palavrasDescricao)) pontos += 1
        return pontos
    }

    /** Extrai palavras/expressões potencialmente úteis da própria description (>=4 letras). */
    private fun descricaoComoTermos(description: String): List<String> =
        Regex("[a-zA-ZÀ-ÿ]{4,}").findAll(description.lowercase())
            .map { it.value }
            .filterNot { it in STOPWORDS_EN }
            .distinct()
            .toList()

    private val STOPWORDS_EN = setOf(
        "use", "when", "with", "that", "this", "your", "have", "need", "from", "into",
        "about", "each", "such", "than", "then", "before", "after", "which", "does",
        "guides", "guide", "using", "used"
    )

    /** Gatilhos em português, curados a partir da description de cada SKILL.md do RootFS 0.6. */
    private val GATILHOS_PT: Map<String, Set<String>> = mapOf(
        "api-and-interface-design" to setOf("api", "endpoint", "rest", "graphql", "contrato", "interface publica", "integracao"),
        "browser-testing-with-devtools" to setOf("navegador", "browser", "devtools", "console do navegador", "requisicao de rede"),
        "ci-cd-and-automation" to setOf("ci", "cd", "pipeline", "esteira", "deploy automatico", "integracao continua"),
        "code-review-and-quality" to setOf("revisao de codigo", "code review", "qualidade do codigo", "merge", "pull request"),
        "code-simplification" to setOf("simplificar", "refatorar", "codigo complexo", "legibilidade"),
        "constraint-driven-development" to setOf("padrao de qualidade", "cobertura de teste", "contrato de qualidade", "constraints"),
        "context-engineering" to setOf("contexto do agente", "nova sessao", "regras do projeto"),
        "debugging-and-error-recovery" to setOf("bug", "erro", "quebrou", "nao funciona", "depurar", "debugar", "falha no teste", "stacktrace"),
        "deprecation-and-migration" to setOf("migrar", "migracao", "descontinuar", "depreciar", "remover sistema antigo"),
        "documentation-and-adrs" to setOf("documentar", "documentacao", "adr", "decisao de arquitetura", "registrar decisao"),
        "doubt-driven-development" to setOf("revisao critica", "questionar premissa", "alto risco", "producao critica"),
        "frontend-ui-engineering" to setOf("tela", "interface do usuario", "ui", "layout", "componente visual", "frontend", "acessibilidade", "responsivo"),
        "git-workflow-and-versioning" to setOf("git", "commit", "branch", "pull request", "versionamento", "release", "changelog"),
        "idea-refine" to setOf("refinar ideia", "ideia vaga", "brainstorm"),
        "incremental-implementation" to setOf("implementar aos poucos", "entrega incremental", "feature flag", "passo a passo"),
        "interview-me" to setOf("pedido vago", "nao sei bem o que quero", "me entreviste"),
        "observability-and-instrumentation" to setOf("log", "logging", "metrica", "monitoramento", "tracing", "alerta", "observabilidade"),
        "performance-optimization" to setOf("performance", "desempenho", "lentidao", "query lenta", "otimizar", "gargalo"),
        "planning-and-task-breakdown" to setOf("planejar", "plano", "dividir em tarefas", "quebrar em passos", "escopo"),
        "security-and-hardening" to setOf("seguranca", "vulnerabilidade", "autenticacao", "dados sensiveis", "owasp", "login seguro"),
        "shipping-and-launch" to setOf("lancamento", "lancar em producao", "rollout", "checklist de lancamento", "rollback"),
        "source-driven-development" to setOf("documentacao oficial", "verificar na doc", "biblioteca", "framework"),
        "spec-driven-development" to setOf("especificacao", "requisitos", "prd", "escopo do projeto novo"),
        "test-driven-development" to setOf("teste", "tdd", "testes unitarios", "cobrir com teste", "corrigir bug com teste"),
        "using-agent-skills" to emptySet()
    )
}
