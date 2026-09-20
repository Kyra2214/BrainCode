package com.brain.planning

import com.brain.reasoning.ReasoningState
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.UUID

data class PlanningArtifact(
    val runId: String,
    val artifactId: String,
    val idea: String,
    val requirements: List<String>,
    val decisions: List<String>,
    val pending: List<String>,
    val references: List<String>,
    val assumptions: List<String>,
    val status: PlanningStatus,
    val createdAt: Instant = Instant.now()
) {
    init { require(runId.isNotBlank() && idea.isNotBlank()) }

    fun toJson(): JSONObject = JSONObject().apply {
        put("runId", runId); put("artifactId", artifactId); put("idea", idea)
        put("requirements", JSONArray(requirements)); put("decisions", JSONArray(decisions))
        put("pending", JSONArray(pending)); put("references", JSONArray(references))
        put("assumptions", JSONArray(assumptions)); put("status", status.name)
        put("createdAt", createdAt.toString())
    }

    companion object {
        fun fromJson(json: JSONObject): PlanningArtifact = PlanningArtifact(
            runId = json.getString("runId"), artifactId = json.getString("artifactId"), idea = json.getString("idea"),
            requirements = json.array("requirements"), decisions = json.array("decisions"),
            pending = json.array("pending"), references = json.array("references"), assumptions = json.array("assumptions"),
            status = PlanningStatus.valueOf(json.getString("status")), createdAt = Instant.parse(json.getString("createdAt"))
        )
        private fun JSONObject.array(key: String): List<String> = (optJSONArray(key) ?: JSONArray()).let { array ->
            (0 until array.length()).map(array::getString)
        }
    }
}

enum class PlanningStatus { READY, NEEDS_CLARIFICATION }

class PlanningAgent {
    fun plan(runId: String, state: ReasoningState, references: List<String> = emptyList()): PlanningArtifact {
        val context = state.contextPack
        return PlanningArtifact(
            runId = runId,
            artifactId = "planning-${UUID.randomUUID()}",
            idea = context.objective,
            requirements = context.requirements,
            decisions = context.decisions + "intent=${context.intent}" + "domain=${context.domain}",
            pending = context.missingRequirements,
            references = references.distinct(),
            assumptions = context.assumptions,
            status = if (context.missingRequirements.isEmpty()) PlanningStatus.READY else PlanningStatus.NEEDS_CLARIFICATION
        )
    }
}

interface PlanningArtifactStore {
    fun save(artifact: PlanningArtifact)
    fun get(runId: String): PlanningArtifact?
}

class FilePlanningArtifactStore(private val file: File) : PlanningArtifactStore {
    private val artifacts = linkedMapOf<String, PlanningArtifact>()
    init {
        file.parentFile?.mkdirs()
        if (file.isFile) file.readLines().forEach { line -> runCatching { PlanningArtifact.fromJson(JSONObject(line)) }.getOrNull()?.let { artifacts[it.runId] = it } }
    }
    @Synchronized override fun save(artifact: PlanningArtifact) {
        artifacts[artifact.runId] = artifact
        val temp = File(file.parentFile ?: File("."), "${file.name}.tmp")
        temp.writeText(artifacts.values.joinToString("\n") { it.toJson().toString() } + "\n")
        check(temp.renameTo(file)) { "não foi possível persistir PlanningArtifact" }
    }
    @Synchronized override fun get(runId: String): PlanningArtifact? = artifacts[runId]
}
