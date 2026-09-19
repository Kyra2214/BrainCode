package com.brain.planner

import com.brain.execution.RiskClass
import com.brain.behavior.AcceptanceCriteria
import com.brain.behavior.AcceptanceCriterionContract
import com.brain.reasoning.ReasoningState
import com.brain.router.PapelPipeline

/**
 * Um passo dentro de um [PlanoExecucao]: uma capacidade a autorizar/rodar
 * dentro do Sandbox (Etapas 2-4), mais os metadados que o Ciclo (Etapa 6,
 * ver `com.sandbox.agent.CicloExecucaoPlano` em `:android-module`) precisa
 * pra decidir ordem, autorização e critério de aprovação.
 *
 * @property papel quando não nulo, o Ciclo pede ao [com.brain.router.AIRouter]
 *   pra escolher provider/modelo antes de rodar este passo (Etapa 5). Passos
 *   que só executam uma capacidade fixa do Sandbox (ex.: `sandbox.test`) não
 *   precisam disso e deixam `papel = null`.
 * @property criterioSucesso hoje é só metadado descritivo (para log/auditoria
 *   e para quem lê o plano) — o Ciclo ainda não faz correspondência mecânica
 *   entre este texto e o resultado da validação; quem decide passa/falha é
 *   sempre a evidência do [com.brain.qa.ExecutorValidacaoProjeto].
 */
data class PassoPlano(
    val id: String,
    val capacidade: String,
    val criterioSucesso: String,
    val parametros: List<String> = emptyList(),
    val dependeDe: List<String> = emptyList(),
    val papel: PapelPipeline? = null,
    val riskClass: RiskClass = RiskClass.LOW,
    val idempotent: Boolean = true,
    val idempotencyKey: String? = null,
    val acceptanceCriteria: List<AcceptanceCriteria> = listOf(
        AcceptanceCriteria("success", criterioSucesso, verification = "evidence:step-result")
    )
) {
    val structuredAcceptanceCriteria: List<AcceptanceCriterionContract>
        get() = acceptanceCriteria.map { it.structured() }

    init {
        require(id.isNotBlank()) { "id do passo não pode ser vazio" }
        require(capacidade.isNotBlank()) { "capacidade do passo não pode ser vazia" }
        require(criterioSucesso.isNotBlank()) { "criterioSucesso do passo não pode ser vazio" }
        require(id !in dependeDe) { "passo '$id' não pode depender de si mesmo" }
        if (!idempotent) require(!idempotencyKey.isNullOrBlank()) { "operação não idempotente exige idempotencyKey" }
        require(acceptanceCriteria.isNotEmpty()) { "passo precisa de ao menos um acceptance criterion" }
        require(acceptanceCriteria.map { it.id }.distinct().size == acceptanceCriteria.size) { "acceptance criteria duplicados" }
    }
}

/**
 * Objetivo decomposto em passos (Etapa 6). Valida, na construção, que os
 * ids são únicos, que toda dependência aponta pra um passo que existe
 * neste mesmo plano, e que o grafo de dependências não tem ciclo — um
 * plano cíclico nunca chega a virar instância válida, então o Ciclo
 * (`CicloExecucaoPlano`) nunca precisa lidar com esse caso em tempo de
 * execução.
 *
 * [ordemDeExecucao] já vem calculada (ordenação topológica, ordem estável
 * pra empates) — é essa lista, não [passos], que o Ciclo deve percorrer.
 */
data class PlanoExecucao(
    val objetivo: String,
    val passos: List<PassoPlano>,
    val assumptions: Set<String> = emptySet(),
    val policies: Set<String> = emptySet(),
    val fallback: String? = null,
    val missingRequirements: List<String> = emptyList()
) {
    val ordemDeExecucao: List<PassoPlano>
    val requiredCapabilities: Set<String>
    val tasks: List<Task>

    init {
        require(objetivo.isNotBlank()) { "objetivo não pode ser vazio" }
        require(passos.isNotEmpty()) { "plano precisa de ao menos um passo" }

        val ids = passos.map { it.id }
        val duplicados = ids.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        require(duplicados.isEmpty()) { "ids de passo duplicados: $duplicados" }

        val idsValidos = ids.toSet()
        passos.forEach { passo ->
            val inexistentes = passo.dependeDe.filterNot { it in idsValidos }
            require(inexistentes.isEmpty()) {
                "passo '${passo.id}' depende de id inexistente: $inexistentes"
            }
        }

        ordemDeExecucao = ordenarTopologicamente(passos)
        requiredCapabilities = passos.map { it.capacidade }.toSet()
        tasks = passos.map { passo ->
            Task(
                id = passo.id,
                objective = passo.criterioSucesso,
                inputs = passo.parametros,
                dependencies = passo.dependeDe.toSet(),
                capabilities = setOf(passo.capacidade),
                retryLimit = 0,
                validation = passo.criterioSucesso,
                acceptanceCriteria = passo.acceptanceCriteria
            )
        }
    }

    private fun ordenarTopologicamente(passos: List<PassoPlano>): List<PassoPlano> {
        val porId = passos.associateBy { it.id }
        val grauEntrada = passos.associate { it.id to it.dependeDe.size }.toMutableMap()
        val dependentes = mutableMapOf<String, MutableList<String>>()
        passos.forEach { passo ->
            passo.dependeDe.forEach { dep -> dependentes.getOrPut(dep) { mutableListOf() }.add(passo.id) }
        }

        val fila = ArrayDeque(passos.filter { it.dependeDe.isEmpty() }.map { it.id })
        val ordem = mutableListOf<String>()
        while (fila.isNotEmpty()) {
            val atual = fila.removeFirst()
            ordem += atual
            dependentes[atual].orEmpty().forEach { dependente ->
                val restante = grauEntrada.getValue(dependente) - 1
                grauEntrada[dependente] = restante
                if (restante == 0) fila.addLast(dependente)
            }
        }

        check(ordem.size == passos.size) {
            "dependência cíclica entre passos: ${passos.map { it.id }.filterNot { it in ordem }}"
        }
        return ordem.map { porId.getValue(it) }
    }
}

/** Nome universal do plano mestre; mantém PlanoExecucao como API existente. */
typealias ExecutionPlan = PlanoExecucao

/**
 * Decompõe um objetivo em texto livre num [PlanoExecucao]. A implementação
 * determinística ativa é plugada pelo Ciclo Android antes da autorização.
 */
interface Planner {
    suspend fun planejar(objetivo: String): PlanoExecucao

    /** Entrada canônica: o plano recebe o raciocínio já calculado, em vez de descartá-lo. */
    suspend fun planejar(objetivo: String, reasoning: ReasoningState): PlanoExecucao =
        planejar(objetivo).copy(
            assumptions = reasoning.assumptions.toSet(),
            fallback = if (reasoning.missing.isEmpty()) null else "prosseguir-localmente-com-suposições-explicitas",
            missingRequirements = reasoning.missing,
            contextPack = reasoning.contextPack
        )
}
