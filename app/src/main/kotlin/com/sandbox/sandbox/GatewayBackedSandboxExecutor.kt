package com.sandbox.sandbox

import com.brain.capability.CapabilityDefinition
import com.brain.gateway.ActionExecutor
import com.brain.gateway.ActionGateway
import com.brain.gateway.ActionRequest
import com.brain.policy.PolicyContext
import com.sandbox.runtime.ExecutionLog
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Executor do Gateway que traduz capability declarativa em comando allowlisted
 * e chama o runtime Sandbox existente. Não recebe shell bruto do Brain.
 */
class SandboxActionExecutor(
    private val delegate: SandboxCommandExecutor,
    private val executionLogs: MutableMap<String, ExecutionLog>
) : ActionExecutor {
    override fun execute(
        request: ActionRequest,
        capability: CapabilityDefinition,
        decision: com.brain.policy.PolicyDecision
    ): com.brain.gateway.ActionExecution {
        val executable = request.parameters["executable"] ?: executableFor(capability.id)
        // Tool executables may contain '+' (e.g. g++). Keep this a lexical guard,
        // while capabilityFor() remains the allowlist boundary for what may run.
        require(executable.matches(Regex("^[a-zA-Z0-9._/+\\-]+$"))) { "executável não permitido" }
        val args = request.parameters
            .filterKeys { it.startsWith("argument.") }
            .toSortedMap()
            .values
        val timeout = request.parameters["timeoutSeconds"]?.toLongOrNull() ?: 60L
        val log = delegate.execute(listOf(executable) + args, timeout, request.resource)
        executionLogs[request.actionId] = log
        return com.brain.gateway.ActionExecution(
            success = log.succeeded,
            result = log.stdout,
            error = log.stderr.takeIf { !log.succeeded },
            evidence = listOf("sandbox:${log.executionId}", "termination:${log.terminationReason}"),
            provenance = listOf("android:sandbox-runtime")
        )
    }

    private fun executableFor(capability: String): String = when (capability) {
        "sandbox.git" -> "git"
        "sandbox.diagnostics" -> "ps"
        "sandbox.toolchain" -> "python3"
        "sandbox.test" -> "sandbox-test"
        "sandbox.plugin" -> "sandbox-plugin"
        else -> error("capability Sandbox não mapeada: $capability")
    }
}

/**
 * Ponte Android → ActionGateway → Sandbox. As APIs antigas continuam vendo
 * apenas SandboxCommandExecutor, mas toda execução passa pelo Gateway.
 */
class GatewayBackedSandboxExecutor(
    private val gateway: ActionGateway,
    private val executionLogs: MutableMap<String, ExecutionLog>,
    private val actor: String = "sandbox-platform"
) : SandboxCommandExecutor {
    override fun execute(command: List<String>, timeoutSeconds: Long, workingDir: String): ExecutionLog {
        require(command.isNotEmpty()) { "comando vazio" }
        val capability = capabilityFor(command.first())
        val actionId = "android-action-${UUID.randomUUID()}"
        val parameters = mapOf("executable" to command.first(), "timeoutSeconds" to timeoutSeconds.toString()) +
            command.drop(1).mapIndexed { index, argument -> "argument.%04d".format(index) to argument }
        val result = gateway.execute(
            ActionRequest(
                actionId = actionId,
                actor = actor,
                capability = capability,
                parameters = parameters,
                resource = workingDir,
                context = PolicyContext(
                    runId = "android-sandbox",
                    taskId = actionId,
                    actor = actor,
                    riskClass = com.brain.execution.RiskClass.LOW,
                    sandboxRequired = true,
                    filesystemRoots = listOf(workingDir)
                ),
                provenance = listOf("android:sandbox", "command:${command.first()}")
            )
        )
        check(result.success) { result.execution?.error ?: "ActionGateway bloqueou $capability" }
        return executionLogs.remove(actionId)
            ?: error("executor do Sandbox não devolveu ExecutionLog para $actionId")
    }

    private fun capabilityFor(executable: String): String = when (executable.substringAfterLast('/')) {
        "git" -> "sandbox.git"
        "ps", "uname", "id" -> "sandbox.diagnostics"
        "python", "python3", "node", "gcc", "g++", "javac", "go" -> "sandbox.toolchain"
        "sandbox-build", "sandbox-test" -> "sandbox.test"
        else -> "sandbox.plugin"
    }

    companion object {
        fun logs(): MutableMap<String, ExecutionLog> = ConcurrentHashMap()
    }
}
