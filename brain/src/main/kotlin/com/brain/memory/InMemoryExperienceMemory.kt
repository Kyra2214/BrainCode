package com.brain.memory

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Implementação mínima do ExperienceMemory (Fase D). Guarda cada
 * Experiencia numa lista em memória — SQLite/Room (ver comentário do
 * stub original) entra quando a Fase D virar persistência real; a
 * interface e o cálculo de taxaSucessoPorEstrategia não mudam, só o
 * storage por trás.
 *
 * Falha é dado, não ausência de dado: registrar() nunca filtra por
 * resultado. CORRIGIDO_APOS_FALHA conta como sucesso parcial (0.5) em
 * taxaSucessoPorEstrategia — a estratégia funcionou, mas só depois de
 * uma correção, então não recebe o mesmo peso de acertar de primeira.
 */
class InMemoryExperienceMemory : ExperienceMemory {

    private val experiencias = CopyOnWriteArrayList<Experiencia>()

    override suspend fun registrar(experiencia: Experiencia) {
        experiencias += experiencia
    }

    override suspend fun buscarPorTarefa(tarefaId: String): List<Experiencia> =
        experiencias.filter { it.tarefaId == tarefaId }

    override suspend fun taxaSucessoPorEstrategia(estrategia: String): Double {
        val relevantes = experiencias.filter { it.estrategiaUsada == estrategia }
        // Sem histórico ainda: neutro — mesma convenção do router quando falta LiveStats.
        if (relevantes.isEmpty()) return 0.5

        val pontos = relevantes.sumOf { experiencia ->
            when (experiencia.resultado) {
                ResultadoExperiencia.SUCESSO -> 1.0
                ResultadoExperiencia.CORRIGIDO_APOS_FALHA -> 0.5
                ResultadoExperiencia.FALHA -> 0.0
            }
        }
        return pontos / relevantes.size
    }
}
