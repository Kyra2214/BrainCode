package com.brain.behavior

import com.brain.memory.LayeredMemory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ValidatedLearningTest {
    private fun ready() = ReadinessEvaluator().evaluate(
        ReadinessInput(
            WorkKind.EXECUTION,
            ReadinessStageName.entries.associateWith { true },
            ReadinessStageName.entries.associateWith { listOf("evidence:${it.name}") }
        )
    )

    private fun candidate(verified: Boolean = true, passed: Boolean = true) = LearningCandidate(
        "run", "task", "problem", "strategy", "result",
        ExecutionEvidence("e1", "test", "test passou", "ci", verified),
        VerificationResult(if (passed) VerificationStatus.PASSED else VerificationStatus.FAILED, listOf(VerificationCheck("c", passed, "check"))),
        CritiqueResult(if (passed) CritiqueStatus.PASS else CritiqueStatus.FAIL, if (passed) emptyList() else listOf(CritiqueFinding("f", "falhou"))),
        ready()
    )

    @Test fun `learning validado persiste procedimento`() {
        val memory = LayeredMemory()
        val result = ValidatedLearning(memory).record(candidate())
        assertTrue(result.isSuccess)
        assertEquals(1, memory.procedures().size)
        assertTrue(memory.procedures().single().validated)
    }

    @Test fun `learning rejeita evidencia nao verificada`() {
        val result = ValidatedLearning(LayeredMemory()).record(candidate(verified = false))
        assertTrue(result.isFailure)
    }

    @Test fun `learning rejeita verification falha`() {
        val result = ValidatedLearning(LayeredMemory()).record(candidate(passed = false))
        assertTrue(result.isFailure)
    }
}
