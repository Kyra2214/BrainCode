package com.sandbox.sandbox

import com.sandbox.runtime.ExecutionLog
import java.io.File

data class SandboxLimits(
    val maxTimeoutSeconds: Long = 600,
    val maxOutputChars: Int = 256 * 1024,
    val maxProcesses: Int = 128,
    val maxWorkspaceBytes: Long = 2L * 1024 * 1024 * 1024
)

data class SandboxSecurityPolicy(
    val allowedWorkingRoots: List<String> = listOf("/home/sandbox", "/tmp"),
    val allowNetwork: Boolean = true,
    val blockedCommands: Set<String> = setOf("mkfs", "mount", "umount", "reboot", "shutdown", "poweroff", "dd"),
    val limits: SandboxLimits = SandboxLimits()
)

class SecureCommandExecutor(private val delegate: SandboxCommandExecutor, private val policy: SandboxSecurityPolicy = SandboxSecurityPolicy()) : SandboxCommandExecutor {
    override fun execute(command: List<String>, timeoutSeconds: Long, workingDir: String): ExecutionLog {
        require(command.isNotEmpty()) { "Comando vazio" }
        require(timeoutSeconds in 1..policy.limits.maxTimeoutSeconds) { "Timeout excede o limite do Sandbox" }
        require(policy.allowedWorkingRoots.any { workingDir == it || workingDir.startsWith("$it/") }) { "Diretório de trabalho não permitido" }
        val executable = command.first().substringAfterLast('/')
        require(executable !in SHELL_INTERPRETERS) { "shell livre não é uma capacidade autorizada" }
        val normalized = command.joinToString(" ").lowercase()
        val blocked = policy.blockedCommands.firstOrNull { token ->
            Regex("(^|[^a-z0-9_/-])${Regex.escape(token.lowercase())}([^a-z0-9_/-]|$)").containsMatchIn(normalized)
        }
        require(blocked == null) { "Comando bloqueado pela política de segurança: $blocked" }
        require(policy.allowNetwork || !normalized.contains(Regex("curl|wget|nc|ssh|git[[:space:]]+clone|/dev/tcp"))) { "Rede bloqueada pela política do Sandbox" }
        return delegate.execute(command, timeoutSeconds, workingDir)
    }

    private companion object {
        val SHELL_INTERPRETERS = setOf("sh", "bash", "ash", "dash", "zsh", "fish", "cmd", "powershell")
    }
}

data class ProcessSnapshot(val pid: Long, val command: String, val state: String, val isOrphan: Boolean)

class SandboxDiagnostics(private val executor: SandboxCommandExecutor) {
    fun processes(): List<ProcessSnapshot> {
        val result = executor.execute(listOf("ps", "-eo", "pid=,stat=,args="), 30)
        return result.stdout.lineSequence().mapNotNull { line ->
            val parts = line.trim().split(Regex("\\s+"), limit = 3)
            if (parts.size < 3) null else ProcessSnapshot(parts[0].toLongOrNull() ?: return@mapNotNull null, parts[2], parts[1], parts[1].contains("Z"))
        }.toList()
    }
    fun workspaceSize(workspace: File): Long = workspace.walkTopDown().filter { it.isFile }.fold(0L) { total, file -> total + file.length() }
}
