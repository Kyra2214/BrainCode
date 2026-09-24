package com.sandbox.runtime

/**
 * Resultado de um comando executado dentro do sandbox.
 *
 * [timedOut] é explícito em vez de inferido do exitCode, porque um comando
 * pode legitimamente sair com código != 0 por conta própria — misturar os
 * dois conceitos escondia esse tipo de causa em versões anteriores de
 * projetos parecidos.
 */
data class SandboxExecutionResult(
    val stdout: String,
    val stderr: String,
    val exitCode: Int,
    val timedOut: Boolean,
    // null = nenhum RLIMIT foi pedido (ver ProotResourceLimits.NONE) e não
    // há nada a verificar; true = o ulimit efetivo bateu com o pedido;
    // false = pedido, mas o kernel não confirmou o valor esperado (ver
    // ProotResourceLimits.kt e ResourceLimitVerification). `false` deve ser
    // tratado como falha de isolamento pelo chamador.
    val resourceLimitsVerified: Boolean? = null
) {
    val succeeded: Boolean get() = !timedOut && exitCode == 0
}
