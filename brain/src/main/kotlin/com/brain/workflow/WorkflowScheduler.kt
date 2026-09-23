package com.brain.workflow

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

/** Estado persistido de um workflow agendado; não executa o corpo do documento. */
data class ScheduledWorkflow(
    val id: String,
    val version: String,
    val expression: String,
    val zoneId: String,
    val nextRun: Instant,
    val claimedBy: String? = null,
    val claimUntil: Instant? = null
)

class WorkflowScheduler(private val stateFile: File? = null) {
    private val states = ConcurrentHashMap<String, ScheduledWorkflow>()

    init { load() }

    @Synchronized
    fun register(document: WorkflowDocument, zoneId: String = "UTC", now: Instant = Instant.now()): ScheduledWorkflow? {
        val expression = document.schedule?.takeIf { it.isNotBlank() } ?: return null
        val zone = ZoneId.of(zoneId)
        val next = WorkflowSchedule(expression).nextAfter(now, zone) ?: return null
        val scheduled = ScheduledWorkflow(document.id, document.version, expression, zone.id, next)
        states[document.id] = scheduled
        persist()
        return scheduled
    }

    @Synchronized
    fun unregister(id: String) { states.remove(id); persist() }

    fun due(now: Instant = Instant.now()): List<ScheduledWorkflow> = states.values
        .filter { !it.nextRun.isAfter(now) && (it.claimUntil == null || it.claimUntil.isBefore(now)) }
        .sortedBy { it.nextRun }

    @Synchronized
    fun claim(id: String, owner: String, now: Instant = Instant.now(), leaseSeconds: Long = 300): ScheduledWorkflow {
        require(owner.isNotBlank()) { "owner obrigatório" }
        require(leaseSeconds > 0) { "lease deve ser positivo" }
        val current = states[id] ?: throw NoSuchElementException("workflow não agendado: $id")
        check(!current.nextRun.isAfter(now)) { "workflow ainda não está devido" }
        check(current.claimUntil == null || current.claimUntil.isBefore(now) || current.claimedBy == owner) {
            "workflow já possui claim ativo"
        }
        val claimed = current.copy(claimedBy = owner, claimUntil = now.plusSeconds(leaseSeconds))
        states[id] = claimed
        persist()
        return claimed
    }

    @Synchronized
    fun complete(id: String, owner: String, now: Instant = Instant.now()): ScheduledWorkflow {
        val current = states[id] ?: throw NoSuchElementException("workflow não agendado: $id")
        check(current.claimedBy == owner) { "owner do claim não corresponde" }
        val next = WorkflowSchedule(current.expression).nextAfter(now, ZoneId.of(current.zoneId))
            ?: throw IllegalStateException("schedule deixou de produzir próxima execução")
        return current.copy(nextRun = next, claimedBy = null, claimUntil = null).also {
            states[id] = it
            persist()
        }
    }

    fun get(id: String): ScheduledWorkflow? = states[id]

    private fun persist() {
        stateFile?.let { file ->
            file.parentFile?.mkdirs()
            file.writeText(JSONArray(states.values.map { state ->
                JSONObject().put("id", state.id).put("version", state.version).put("expression", state.expression)
                    .put("zoneId", state.zoneId).put("nextRun", state.nextRun.toString())
                    .put("claimedBy", state.claimedBy ?: JSONObject.NULL).put("claimUntil", state.claimUntil?.toString() ?: JSONObject.NULL)
            }).toString())
        }
    }

    private fun load() {
        val file = stateFile?.takeIf { it.isFile } ?: return
        runCatching {
            val array = JSONArray(file.readText())
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                states[item.getString("id")] = ScheduledWorkflow(
                    id = item.getString("id"),
                    version = item.getString("version"),
                    expression = item.getString("expression"),
                    zoneId = item.getString("zoneId"),
                    nextRun = Instant.parse(item.getString("nextRun")),
                    claimedBy = item.optString("claimedBy").takeUnless { it == "null" || it.isBlank() },
                    claimUntil = item.optString("claimUntil").takeUnless { it == "null" || it.isBlank() }?.let(Instant::parse)
                )
            }
        }
    }
}
