package com.brain.execution

import com.brain.gateway.PreActionCheckResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReActLoopTest {
    @Test
    fun `loop observa cada passo antes de seguir`() {
        val seen = mutableListOf<String>()
        val result = ReActLoop().run(
            "executar sequência segura",
            listOf(OperationalStep("a", "primeiro"), OperationalStep("b", "segundo")),
            preCheck = { PreActionCheckResult(true) },
            execute = { step -> seen += step.id; Observation(step.id, true, "ok") }
        )

        assertEquals(listOf("a", "b"), seen)
        assertEquals(listOf("a", "b"), result.state.completed)
        assertTrue(!result.stopped)
    }

    @Test
    fun `falha de pre check impede proximo passo`() {
        var executions = 0
        val result = ReActLoop().run(
            "operação protegida",
            listOf(OperationalStep("danger", "ação protegida")),
            preCheck = { PreActionCheckResult(false, listOf("sem aprovação")) },
            execute = { executions++; Observation("danger", true, "não deveria executar") }
        )

        assertEquals(0, executions)
        assertTrue(result.stopped)
        assertTrue(result.stopReason.orEmpty().contains("pre-check"))
    }
}
