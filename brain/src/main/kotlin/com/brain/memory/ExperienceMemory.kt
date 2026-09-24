package com.brain.memory

import java.time.Instant

/**
 * Fase D do plano: memória desde o primeiro teste ponta a ponta.
 * Implementação inicial pensada para SQLite (Room, reaproveitando o
 * padrão do IaBrain) + arquivos/JSON para payloads grandes (prompts,
 * saídas). LEANN entra depois, como camada de busca semântica sobre
 * esse mesmo histórico — não substitui o registro básico, complementa.
 *
 * Cada execução do fluxo de 8 partes gera UMA Experiencia, independente
 * de o resultado final ter sido aprovado ou não. Falha é dado, não é
 * ausência de dado.
 */
data class Experiencia(
    val id: String,
    val tarefaId: String,
    val problema: String,           // objetivo original da tarefa
    val estrategiaUsada: String,    // ex.: "RESOLVER_LOCAL" ou provider/modelo escolhido
    val promptUsado: String?,
    val resultado: ResultadoExperiencia,
    val custoEstimado: Double?,     // sempre 0.0 hoje (zero-custo), mas o campo já existe para o dia em que mudar
    val tempoTotalMs: Long,
    val erros: List<String>,
    val registradoEm: Instant
)

enum class ResultadoExperiencia { SUCESSO, FALHA, CORRIGIDO_APOS_FALHA }

interface ExperienceMemory {
    suspend fun registrar(experiencia: Experiencia)
    suspend fun buscarPorTarefa(tarefaId: String): List<Experiencia>

    /**
     * Consulta usada pelo AIRouter para ajustar RoutingProfile.qualityScore
     * com o tempo, a partir de experiências reais em vez de score fixo.
     */
    suspend fun taxaSucessoPorEstrategia(estrategia: String): Double
}
