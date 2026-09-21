package com.brain.core

/**
 * Parte 2 do fluxo: o que o usuário pediu, já interpretado.
 * Substitui o ProjetoIntentParser do IaBrain — mesma ideia, reescrita.
 */
data class UserRequest(
    val rawText: String
)

data class ProjectIntent(
    val originalRequest: UserRequest,
    val projectType: String,        // ex.: "app android", "site", "automação"
    val platform: String?,          // ex.: "android", "web"
    val complexity: Complexity,
    val areasInvolvidas: List<String>, // ex.: ["backend", "frontend", "player/multimidia"]
    val podeResolverLocal: Boolean  // decidido pelo pipeline de planejamento ativo
)

enum class Complexity { BAIXA, MEDIA, ALTA }

/**
 * Parte 3 do fluxo: o roadmap devolvido por uma IA de planejamento,
 * já dividido em fase/módulo/submódulo.
 */
data class Roadmap(
    val projectIntent: ProjectIntent,
    val fases: List<Fase>
)

data class Fase(
    val nome: String,
    val modulos: List<Modulo>
)

data class Modulo(
    val nome: String,
    val submodulos: List<Submodulo>
)

data class Submodulo(
    val nome: String,
    val tarefas: List<Tarefa>
)

data class Tarefa(
    val id: String,
    val descricao: String,
    val statusAtual: TarefaStatus = TarefaStatus.PENDENTE,
    val responsibleAgentId: String? = null,
    val dependencies: List<String> = emptyList()
)

enum class TarefaStatus { PENDENTE, PROMPT_GERADO, EM_EXECUCAO, AGUARDANDO_QA, APROVADA, REPROVADA }
