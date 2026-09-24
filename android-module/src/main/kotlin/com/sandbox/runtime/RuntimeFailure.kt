package com.sandbox.runtime

/** Falha classificada do runtime, com código estável para diagnóstico e métricas. */
class RuntimeFailure(
    val code: String,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
