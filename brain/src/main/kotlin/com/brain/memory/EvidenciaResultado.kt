package com.brain.memory

/**
 * Julga se um resultado textual de execução é evidência válida de
 * sucesso — adaptado de ProjetoExecucaoRepository.resultadoValido()
 * do IaBrain (data/local/ProjetoExecucaoRepository.kt). Mesma regra:
 * só é evidência quando contém TODAS as seções do relatório
 * contratual, NÃO tem nenhum termo negativo, E tem pelo menos um
 * termo positivo. "A IA diz que terminou" não é a mesma coisa que
 * "terminou com evidência" — por isso a validação é por estrutura do
 * relatório, não por sentimento do texto.
 *
 * Usado antes de registrar() uma Experiencia: o chamador decide
 * SUCESSO/FALHA/CORRIGIDO_APOS_FALHA com base nisso, não a própria
 * ExperienceMemory (ela só guarda o que já foi decidido).
 */
object EvidenciaResultado {

    private val secoesObrigatorias = listOf(
        "TAREFA:", "ALTERAÇÕES:", "TESTES:", "VALIDAÇÃO:",
        "FUNCIONALIDADE:", "RESULTADO:", "LIMITAÇÕES:", "CONCLUSÃO:"
    )
    private val termosNegativos = listOf(
        "NÃO EXECUTADO", "NAO EXECUTADO", "NÃO VERIFICADA", "NAO VERIFICADA",
        "NÃO COMPROVADA", "NAO COMPROVADA", "REPROVADO", "FALHOU", "PENDENTE"
    )
    private val termosPositivos = listOf("APROVADO", "PASSOU", "OK", "CONFORME", "SUCESSO")

    fun valido(resultado: String?): Boolean {
        val texto = resultado?.trim().orEmpty()
        if (texto.isBlank()) return false

        val normalizado = texto.uppercase()
        if (secoesObrigatorias.any { it !in normalizado }) return false
        if (termosNegativos.any { it in normalizado }) return false
        return termosPositivos.any { it in normalizado }
    }
}
