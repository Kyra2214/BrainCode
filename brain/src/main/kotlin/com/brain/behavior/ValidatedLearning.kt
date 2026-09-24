package com.brain.behavior

import com.brain.memory.LayeredMemory
import com.brain.memory.Provenance
import java.time.Instant

data class LearningCandidate(
    val runId: String,
    val taskId: String,
    val problem: String,
    val strategy: String,
    val result: String,
    val evidence: ExecutionEvidence,
    val verification: VerificationResult,
    val critique: CritiqueResult,
    val readiness: ReadinessReport
)

data class LearningRecord(
    val runId: String,
    val taskId: String,
    val strategy: String,
    val result: String,
    val recordedAt: Instant
)

class ValidatedLearning(private val memory: LayeredMemory) {
    fun record(candidate: LearningCandidate): Result<LearningRecord> {
        if (!candidate.evidence.verified) return Result.failure(IllegalArgumentException("learning requires verified evidence"))
        if (!candidate.verification.passed) return Result.failure(IllegalArgumentException("learning requires passed verification"))
        if (candidate.critique.status != CritiqueStatus.PASS) return Result.failure(IllegalArgumentException("learning requires passing critique"))
        if (candidate.readiness.status != ReadinessStatus.READY || candidate.readiness.stages.any { !it.passed }) {
            return Result.failure(IllegalArgumentException("learning requires READY report"))
        }
        val record = LearningRecord(candidate.runId, candidate.taskId, candidate.strategy, candidate.result, Instant.now())
        memory.rememberProcedure(
            "${record.strategy}: ${record.result}",
            Provenance("learning:${record.runId}:${record.taskId}", confidence = 1.0),
            validated = true
        )
        return Result.success(record)
    }
}
