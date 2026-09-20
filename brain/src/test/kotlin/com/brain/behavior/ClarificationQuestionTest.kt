package com.brain.behavior

import com.brain.prompt.PromptDomain
import com.brain.reasoning.ReasoningIntent
import com.brain.reasoning.ReasoningState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClarificationQuestionTest {
    @Test
    fun `RequirementGate gera pergunta estruturada para lacuna`() {
        val state = ReasoningState("objetivo", ReasoningIntent.GENERAL_TEXT, PromptDomain.TEXTO, emptyList(), emptyList(), listOf("sujeito"))
        val gate = RequirementGate().evaluate(state)
        assertEquals(GateStatus.NEEDS_CLARIFICATION, gate.status)
        val question = ClarificationQuestion.from(state.objective, gate.issues)
        assertTrue(question.question.contains("esclarecer"))
        assertTrue(question.missingRequirements.isNotEmpty())
    }
}
