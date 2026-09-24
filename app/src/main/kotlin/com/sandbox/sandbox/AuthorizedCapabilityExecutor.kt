package com.sandbox.sandbox

import com.brain.execution.RiskClass
import com.brain.policy.Decision
import com.brain.policy.PolicyBroker
import com.brain.policy.PolicyContext
import com.sandbox.agent.CapabilityResolver
import com.sandbox.runtime.ExecutionLog

/** Único caminho de execução para capacidades de alto nível autorizadas. */
class AuthorizedCapabilityExecutor(
    private val executor: SandboxCommandExecutor,
    private val resolver: CapabilityResolver = CapabilityResolver(),
    private val policy: PolicyBroker,
    private val actor: String
) {
    fun execute(
        capability: String,
        parameters: List<String> = emptyList(),
        resource: String = capability,
        timeoutSeconds: Long = 60,
        workingDir: String = "/home/sandbox",
        context: PolicyContext = PolicyContext(
            runId = "capability-run",
            taskId = capability,
            actor = actor,
            riskClass = RiskClass.LOW,
            sandboxRequired = true,
            networkAllowed = false,
            filesystemRoots = listOf(workingDir)
        )
    ): ExecutionLog {
        val resolution = resolver.resolve(capability, parameters)
        val command = when (resolution) {
            is CapabilityResolver.Resolution.Comando -> resolution.argv
            is CapabilityResolver.Resolution.Refused -> throw SecurityException(resolution.reason)
        }
        val decision = policy.authorize(actor, capability, resource, context)
        check(decision.decision == Decision.ALLOW && policy.check(decision, workingDir)) {
            "capacidade '$capability' não autorizada: ${decision.reason}"
        }
        return executor.execute(command, timeoutSeconds, workingDir)
    }
}
