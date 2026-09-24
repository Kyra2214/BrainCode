package com.brain.planner

/** Estratégia candidata do planner; não executa ações durante a exploração. */
data class ThoughtBranch(
    val name: String,
    val steps: List<String>,
    val score: Double
)

data class ThoughtSelection(
    val objective: String,
    val selected: ThoughtBranch,
    val explored: List<ThoughtBranch>
)

/** Exploração limitada: só ativa quando a tarefa é ambígua/difícil o bastante. */
class TreeOfThoughts(private val maxBranches: Int = 3) {
    init { require(maxBranches in 2..3) { "Tree of Thoughts deve explorar de 2 a 3 ramos" } }

    fun shouldExplore(objective: String): Boolean {
        val lower = objective.lowercase()
        val difficult = listOf("como", "sem quebrar", "migra", "arquitetura", "dependência", "dependencia", "complexo", "ambíguo", "ambiguo")
        return objective.length > 180 || difficult.count { it in lower } >= 2
    }

    fun explore(objective: String): ThoughtSelection {
        require(shouldExplore(objective)) { "Tree of Thoughts não é necessária para este objetivo" }
        val branches = listOf(
            ThoughtBranch("incremental", listOf("inspecionar estado", "alterar menor superfície", "validar evidência"), 0.85),
            ThoughtBranch("isolated", listOf("criar mudança isolada", "executar verificação", "integrar resultado"), 0.80),
            ThoughtBranch("fallback", listOf("identificar risco", "preparar alternativa segura", "reverter se necessário"), 0.70)
        ).take(maxBranches)
        return ThoughtSelection(objective, branches.maxBy { it.score }, branches)
    }
}
