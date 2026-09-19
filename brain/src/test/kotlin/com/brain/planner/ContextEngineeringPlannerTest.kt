package com.brain.planner

import com.brain.reasoning.ReasoningEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextEngineeringPlannerTest {
    @Test
    fun plannerPreservesReasoningContextPackAsTypedPlanContext() {
        val reasoning = ReasoningEngine().analyze("crie um prompt de um foguete no deserto sem texto")
        val plan = kotlinx.coroutines.runBlocking {
            KeywordPlanner().planejar(reasoning.objective, reasoning)
        }

        val context = plan.contextPack
        assertNotNull(context)
        assertEquals(reasoning.objective, context!!.objective)
        assertEquals(reasoning.intent, context.intent)
        assertTrue(context.requirements.isNotEmpty())
        assertTrue(context.constraints.any { it.contains("texto") })
    }
}
