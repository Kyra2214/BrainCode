package com.sandbox.sandbox

import com.brain.policy.PolicyBroker
import org.junit.Test

class AuthorizedCapabilityExecutorTest {
    private class NeverCalled : SandboxCommandExecutor {
        override fun execute(command: List<String>, timeoutSeconds: Long, workingDir: String) =
            error("executor must not run without authorization")
    }

    @Test(expected = SecurityException::class)
    fun `nega capacidade fora do catalogo antes do executor`() {
        val gateway = AuthorizedCapabilityExecutor(
            executor = NeverCalled(),
            policy = PolicyBroker(),
            actor = "test"
        )
        gateway.execute("terminal.raw")
    }
}
