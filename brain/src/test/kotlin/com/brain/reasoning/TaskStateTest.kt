package com.brain.reasoning

import com.brain.execution.OperationalState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class TaskStateTest {
    @Test
    fun `task state atualiza raciocinio operacao e critica sem perder objetivo`() {
        val reasoning = ReasoningEngine().analyze("crie um prompt de foguete")
        val state = TaskState(reasoning.objective)
            .withReasoning(reasoning)
            .withOperational(OperationalState(reasoning.objective))

        assertEquals("crie um prompt de foguete", state.objective)
        assertNotNull(state.reasoning)
        assertNotNull(state.operational)
    }
}
