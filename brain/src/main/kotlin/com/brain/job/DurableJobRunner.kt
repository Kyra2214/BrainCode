package com.brain.job

import com.brain.workflow.WorkflowEngine
import com.brain.workflow.WorkflowManifest
import com.brain.workflow.WorkflowStatus
import com.brain.workflow.WorkflowStepResult

/** Integração operacional entre JobStore e WorkflowEngine para tarefas retomáveis. */
class DurableJobRunner(private val store: JobStore, private val engine: WorkflowEngine) {
    fun run(
        jobId: String,
        runId: String,
        taskId: String,
        manifest: WorkflowManifest,
        authorize: (String) -> Boolean,
        execute: (com.brain.workflow.WorkflowNode, Int) -> WorkflowStepResult,
        isCancelled: () -> Boolean = { false }
    ): JobRecord {
        val existing = store.get(jobId) ?: store.create(jobId, runId, taskId, mapOf("workflow" to manifest.id))
        if (existing.status in setOf(JobStatus.SUCCEEDED, JobStatus.FAILED, JobStatus.CANCELLED)) return existing
        if (existing.status == JobStatus.CREATED) store.transition(jobId, JobStatus.QUEUED)
        if (store.get(jobId)?.status == JobStatus.QUEUED) store.transition(jobId, JobStatus.RUNNING)
        val result = engine.run(manifest, runId, "job:$jobId", authorize, execute, isCancelled)
        result.steps.flatMap { it.evidence }.takeIf { it.isNotEmpty() }?.let { store.appendEvidence(jobId, *it.toTypedArray()) }
        return when (result.status) {
            WorkflowStatus.COMPLETED -> store.transition(jobId, JobStatus.SUCCEEDED, incrementAttempt = false)
            WorkflowStatus.CANCELLED -> store.transition(jobId, JobStatus.CANCELLED, error = result.error, incrementAttempt = false)
            WorkflowStatus.TIMED_OUT, WorkflowStatus.FAILED -> store.transition(jobId, JobStatus.FAILED, error = result.error, incrementAttempt = false)
            WorkflowStatus.RUNNING -> store.get(jobId)!!
        }
    }
}
