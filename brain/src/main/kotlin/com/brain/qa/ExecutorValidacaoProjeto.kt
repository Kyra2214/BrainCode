package com.brain.qa

import java.io.File

enum class ResultadoValidacao { PASSOU, FALHOU, NAO_EXECUTADO, INDETERMINADO }

/** Evidência de uma tentativa física de rodar um comando; ausência de ferramenta nunca vira sucesso. */
data class EvidenciaComando(
    val comando: String,
    val stdout: String,
    val stderr: String,
    val codigoSaida: Int?,
    val duracaoMs: Long,
    val resultado: ResultadoValidacao,
    val motivo: String? = null
)

/**
 * Roda de verdade os comandos descobertos por DescobertaComandosValidacao
 * e devolve evidência (stdout/stderr/exit code), nunca uma inferência.
 * Adaptado de ProjectValidationExecutor (IaBrain, brain/ProjectValidationExecutor.kt).
 *
 * É essa evidência que alimenta o Experiencia.resultado (memory/) e,
 * junto com EvidenciaResultado (memory/EvidenciaResultado.kt), decide
 * se a tarefa vai pra APROVADA ou REPROVADA (core/TarefaStateMachine.kt).
 */
class ExecutorValidacaoProjeto(private val timeoutSegundos: Long = 300) {

    fun validar(raiz: File, ferramentasDisponiveis: Set<String> = DescobertaComandosValidacao.detectarFerramentas()): List<EvidenciaComando> {
        val descoberta = DescobertaComandosValidacao.descobrir(raiz, ferramentasDisponiveis)
        if (descoberta.comandos.isEmpty()) {
            return descoberta.indisponiveis.map { motivo ->
                EvidenciaComando("", "", "", null, 0, ResultadoValidacao.NAO_EXECUTADO, motivo)
            }
        }
        return descoberta.comandos.map { comando -> executar(raiz, comando, ferramentasDisponiveis) }
    }

    private fun executar(raiz: File, comando: String, ferramentasDisponiveis: Set<String>): EvidenciaComando {
        val executavel = comando.substringBefore(' ')
        val normalizado = executavel.removePrefix("./")
        val permitidos = setOf("./gradlew", "npm", "yarn", "pnpm", "pytest", "python", "python3", "mvn", "./mvnw")

        if (executavel !in permitidos || (normalizado !in ferramentasDisponiveis && !File(raiz, executavel).isFile)) {
            return EvidenciaComando(comando, "", "", null, 0, ResultadoValidacao.NAO_EXECUTADO, "ferramenta ausente ou comando não permitido")
        }

        val inicio = System.nanoTime()
        return try {
            val tokens = comando.split(" ")
            val comandoProcesso = if (executavel == "./gradlew" || executavel == "./mvnw") listOf("sh") + tokens else tokens
            val processo = ProcessBuilder(comandoProcesso).directory(raiz).redirectErrorStream(false).start()
            val codigoSaida = aguardarSaidaDoProcesso(processo, timeoutSegundos * 1_000L)

            if (codigoSaida == null) {
                processo.destroy()
                EvidenciaComando(
                    comando,
                    processo.inputStream.readBytes().decodeToString(),
                    processo.errorStream.readBytes().decodeToString(),
                    null,
                    decorrido(inicio),
                    ResultadoValidacao.FALHOU,
                    "tempo limite excedido"
                )
            } else {
                val saida = processo.inputStream.readBytes().decodeToString()
                val erro = processo.errorStream.readBytes().decodeToString()
                EvidenciaComando(
                    comando, saida, erro, codigoSaida, decorrido(inicio),
                    if (codigoSaida == 0) ResultadoValidacao.PASSOU else ResultadoValidacao.FALHOU
                )
            }
        } catch (erro: Exception) {
            EvidenciaComando(comando, "", erro.message.orEmpty(), null, decorrido(inicio), ResultadoValidacao.NAO_EXECUTADO, "execução indisponível")
        }
    }

    private fun decorrido(inicioNano: Long) = (System.nanoTime() - inicioNano) / 1_000_000
}
