package com.sandbox.sandbox

import com.sandbox.runtime.ExecutionLog
import com.sandbox.runtime.SandboxState
import com.sandbox.runtime.TerminationReason
import java.nio.file.Files
import org.junit.Assert.assertNotNull
import org.junit.Test

class ServiceNetworkPolicyTest {
    private class Executor : SandboxCommandExecutor {
        override fun execute(command: List<String>, timeoutSeconds: Long, workingDir: String): ExecutionLog {
            val now = System.currentTimeMillis()
            return ExecutionLog("id", "session", command, workingDir, now, now, 0, 0, TerminationReason.PROCESS_EXIT, false, false, "", "", SandboxState.READY)
        }
    }

    @Test(expected = IllegalStateException::class)
    fun `servico com porta exige request`() {
        ServiceManager(Executor(), Files.createTempDirectory("services").toFile()).start(BuiltInServices.flask("/home/sandbox"))
    }

    @Test
    fun `servico com porta inicia quando regra corresponde`() {
        val policy = NetworkPolicy(rules = setOf(NetworkRule("flask", "tcp", 5000, setOf("example.com"))))
        val manager = ServiceManager(Executor(), Files.createTempDirectory("services").toFile(), NetworkPolicyBroker(policy))
        assertNotNull(manager.start(BuiltInServices.flask("/home/sandbox"), NetworkAccessRequest("flask", "tcp", 5000, "example.com")))
    }
}
