package com.brain.behavior

enum class WorkKind { CODE, DEBUG, RESEARCH, PROMPT, EXECUTION, INTEGRATION }

enum class ReadinessStageName { IMPLEMENTATION, TESTS, QA, SECURITY, ARCHITECTURE, REGRESSION, RELEASE }

data class DefinitionOfDone(val kind: WorkKind, val criteria: List<String>) {
    init {
        require(criteria.isNotEmpty()) { "Definition of Done precisa de critérios" }
        require(criteria.all { it.isNotBlank() }) { "critério de Definition of Done não pode ser vazio" }
    }

    companion object {
        fun forKind(kind: WorkKind): DefinitionOfDone = DefinitionOfDone(kind, when (kind) {
            WorkKind.CODE -> listOf("implementado", "compilado", "testado", "revisado", "regressão verificada")
            WorkKind.DEBUG -> listOf("reproduzido", "causa localizada", "corrigido", "testado", "regressão verificada", "aprendizado registrado")
            WorkKind.RESEARCH -> listOf("fontes identificadas", "evidência guardada", "síntese validada", "criticidade verificada")
            WorkKind.PROMPT -> listOf("intenção entendida", "requisitos atendidos", "gerado", "criticado", "revisado")
            WorkKind.EXECUTION -> listOf("autorizado", "executado", "evidência registrada", "verificado")
            WorkKind.INTEGRATION -> listOf("contrato integrado", "caller comprovado", "testado", "regressão verificada")
        })
    }
}

data class ReadinessInput(
    val kind: WorkKind,
    val completed: Map<ReadinessStageName, Boolean>,
    val evidence: Map<ReadinessStageName, List<String>> = emptyMap()
) {
    init {
        val ids = evidence.values.flatten()
        require(ids.all { it.isNotBlank() }) { "evidência de readiness não pode ter ID vazio" }
        require(ids.size == ids.distinct().size) {
            "cada estágio de readiness precisa de evidência própria; IDs não podem ser reutilizados"
        }
    }
}

class ReadinessEvaluator {
    fun evaluate(input: ReadinessInput): ReadinessReport {
        val required = ReadinessStageName.entries.map { it }
        val stages = required.map { stage ->
            val passed = input.completed[stage] == true && input.evidence[stage].orEmpty().isNotEmpty()
            ReadinessStage(stage.name.lowercase(), passed, input.evidence[stage].orEmpty(), if (passed) "ok" else "evidence required")
        }
        val blockers = stages.filterNot { it.passed }.map { it.name }
        return ReadinessReport(if (blockers.isEmpty()) ReadinessStatus.READY else ReadinessStatus.BLOCKED, stages, blockers)
    }
}
