package com.brain.delivery

import java.io.File
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.nio.file.Files

data class DeliveryArtifact(val path: File, val sha256: String, val bytes: Long)
data class DeliveryTelemetry(val runId: String, val sessionId: String, val taskId: String, val stepId: String, val eventId: String, val provider: String, val latencyMs: Long, val cost: Double)
data class DeliveryReceipt(val delivered: Boolean, val artifacts: List<DeliveryArtifact>, val telemetry: DeliveryTelemetry, val diagnostics: List<String> = emptyList())

class ObservableDelivery {
    fun publish(root: File, runId: String, sessionId: String, taskId: String, stepId: String, eventId: String, provider: String, startedAt: Instant, cost: Double = 0.0): DeliveryReceipt {
        require(root.isDirectory) { "delivery root must be a directory" }
        val artifacts = root.walkTopDown().filter { it.isFile && !Files.isSymbolicLink(it.toPath()) }.map { file ->
            DeliveryArtifact(file, sha256(file), file.length())
        }.toList()
        val telemetry = DeliveryTelemetry(runId, sessionId, taskId, stepId, eventId, provider, Duration.between(startedAt, Instant.now()).toMillis(), cost)
        return DeliveryReceipt(true, artifacts, telemetry)
    }
    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
}
