package com.brain.skill

import java.io.File

/** Pedido explícito de execução; Skills nunca conseguem criar este pedido sozinhas. */
data class SafeSkillExecutionRequest(
    val executable: String,
    val arguments: List<String> = emptyList(),
    val workspace: File,
    val timeoutMs: Long = 5_000,
    val maxOutputBytes: Int = 64 * 1024,
    val networkAllowed: Boolean = false
)

data class SafeSkillExecutionResult(val exitCode: Int, val stdout: String, val stderr: String, val timedOut: Boolean)

/** Backend fornecido pela camada Sandbox/Policy; o core não cria processos. */
typealias SafeSkillExecutionBackend = (SafeSkillExecutionRequest) -> SafeSkillExecutionResult

/**
 * Validador opt-in para hooks declarados. A execução concreta é injetada pela camada Sandbox
 * autorizada; este componente apenas garante allowlist, workspace, limites e ausência de rede.
 */
class SafeSkillResourceExecutor(
    private val allowedExecutables: Set<String>,
    private val backend: SafeSkillExecutionBackend
) {
    fun execute(request: SafeSkillExecutionRequest): SafeSkillExecutionResult {
        require(request.executable in allowedExecutables) { "executável não permitido: ${request.executable}" }
        require(request.timeoutMs in 1..60_000) { "timeout fora do limite" }
        require(request.maxOutputBytes in 1..1_048_576) { "limite de saída inválido" }
        require(request.workspace.canonicalFile.isDirectory) { "workspace inexistente" }
        require(!request.networkAllowed) { "rede precisa de um executor de política dedicado" }
        val result = backend(request)
        return result.copy(
            stdout = request.backendLimit(result.stdout),
            stderr = request.backendLimit(result.stderr)
        )
    }

    private fun SafeSkillExecutionRequest.backendLimit(value: String): String = value.take(maxOutputBytes)
}
