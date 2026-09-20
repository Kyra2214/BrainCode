package com.sandbox.app

/**
 * O executor de prompts entrega a resposta com um invólucro de apresentação
 * ("Encontrei um prompt de referência ... (estimativa heurística interna — qualidade 81%):")
 * e, às vezes, notas no final. Esse invólucro NÃO faz parte do prompt.
 *
 * Quando o usuário pede "muda / melhore / adicione ..." no turno seguinte, o artefato
 * anterior precisa ser só o prompt — senão o invólucro é reenviado como se fosse
 * conteúdo, é aninhado a cada turno e distorce requisitos, similaridade e a revisão.
 */
object PromptEnvelope {
    private val cabecalho = Regex(
        """(?is)^\s*(?:Melhorei o prompt|Encontrei um prompt|Não encontrei prompt|Criei)[^\n]*?\(estimativa heurística interna\s*[—–-]\s*qualidade\s*\d+%\)\s*:\s*"""
    )
    private val notaIa = Regex("""(?is)\s*\(Melhoria por IA não está disponível[^)]*\)\s*$""")
    private val referenciaBiblioteca = Regex(
        """(?is)\s*Referência da biblioteca considerada \([^)]*\):\s*adaptado ao pedido acima\.?\s*$"""
    )

    /** A mensagem veio do gerador de prompts (tem o cabeçalho de apresentação)? */
    fun temInvolucro(texto: String): Boolean = cabecalho.containsMatchIn(texto)

    fun extrairPrompt(texto: String): String {
        var atual = texto.trim()
        var mudou = true
        while (mudou) {
            mudou = false
            val semCabecalho = cabecalho.replaceFirst(atual, "").trim()
            val semNota = notaIa.replace(semCabecalho, "").trim()
            val semReferencia = referenciaBiblioteca.replace(semNota, "").trim()
            if (semReferencia != atual) {
                atual = semReferencia
                mudou = true
            }
        }
        return atual.ifBlank { texto.trim() }
    }
}
