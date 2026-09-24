package com.brain.qa

/** Espera o processo terminar até o timeout, sem travar em exitValue() antes da hora. */
internal fun aguardarSaidaDoProcesso(processo: Process, timeoutMs: Long): Int? {
    val prazo = System.nanoTime() + timeoutMs * 1_000_000
    while (System.nanoTime() < prazo) {
        try {
            return processo.exitValue()
        } catch (_: IllegalThreadStateException) {
            Thread.sleep(25)
        }
    }
    return try {
        processo.exitValue()
    } catch (_: IllegalThreadStateException) {
        null
    }
}
