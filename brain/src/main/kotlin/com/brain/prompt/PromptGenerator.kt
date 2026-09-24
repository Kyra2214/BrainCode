package com.brain.prompt

import com.brain.core.Roadmap
import com.brain.core.Tarefa

/**
 * Parte 4 do fluxo: uma IA boa em escrita transforma cada tarefa do
 * roadmap em um prompt pronto para execução.
 *
 * Equivalente ao PromptGenerationSpecBuilder + ContextualPromptGenerator
 * do IaBrain, reescrito para operar em lote sobre um Roadmap inteiro em
 * vez de um comando isolado.
 */
data class PromptGerado(
    val tarefaId: String,
    val texto: String,
    val origem: String // ex.: "ROUTER_TASK:<tarefaId>", rastreabilidade como no IaBrain
)

interface PromptGenerator {
    /**
     * Para cada tarefa, primeiro consulta a PromptLibrary (buscarPorContexto)
     * por um template já validado; só gera do zero se não houver template
     * com taxaSucesso aceitável para o contexto. Reuso antes de criação.
     */
    suspend fun gerarPromptsPorRoadmap(roadmap: Roadmap, library: PromptLibrary): List<PromptGerado>
    suspend fun gerarPromptDeCorrecao(tarefa: Tarefa, motivoReprovacao: String, library: PromptLibrary): PromptGerado
}
