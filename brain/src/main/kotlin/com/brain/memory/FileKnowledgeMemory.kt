package com.brain.memory

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Memória local-first: JSONL append-only + índice em memória por fingerprint. */
class FileKnowledgeMemory(private val file: File) : KnowledgeMemory {
    private val lock = Any()
    private val entries = linkedMapOf<String, KnowledgeEntry>()

    init { synchronized(lock) { load() } }

    override fun findValidated(problem: String, minScore: Double, scope: KnowledgeScope, ownerId: String?, projectId: String?): KnowledgeEntry? = synchronized(lock) {
        entries.values.asSequence().filter { it.validated && it.scope == scope }
            .filter { scope != KnowledgeScope.USER || it.ownerId == ownerId }
            .filter { scope != KnowledgeScope.PROJECT || it.projectId == projectId }
            .map { it to similarity(problem, it.problem) }.filter { it.second >= minScore }.maxByOrNull { it.second }?.first
    }

    override fun saveCandidate(entry: KnowledgeEntry): KnowledgeEntry = synchronized(lock) {
        entries.values.firstOrNull { it.fingerprint == entry.fingerprint } ?: entry.also { entries[it.id] = it; append(it) }
    }

    override fun confirm(id: String, confidence: Double, source: KnowledgeSource?): KnowledgeEntry? = synchronized(lock) {
        val current = entries[id] ?: return@synchronized null
        val updated = current.copy(confidence = confidence.coerceIn(0.0, 1.0), validated = true, source = source ?: current.source, confirmations = current.confirmations + 1)
        entries[id] = updated; append(updated); updated
    }

    override fun recordCorrection(id: String, correctedAnswer: String, confidence: Double): KnowledgeEntry? = synchronized(lock) {
        val current = entries[id] ?: return@synchronized null
        val updated = current.copy(answer = correctedAnswer, confidence = confidence.coerceIn(0.0, 1.0), validated = true, confirmations = current.confirmations + 1, fingerprint = fingerprintFor(current.problem, correctedAnswer), correctionHistory = current.correctionHistory + KnowledgeCorrection(current.answer, correctedAnswer, confidence))
        entries[id] = updated; append(updated); updated
    }

    override fun all(): List<KnowledgeEntry> = synchronized(lock) { entries.values.toList() }

    private fun append(entry: KnowledgeEntry) {
        file.parentFile?.mkdirs()
        file.appendText(JSONObject().apply {
            put("id", entry.id); put("problem", entry.problem); put("answer", entry.answer); put("confidence", entry.confidence); put("validated", entry.validated); put("createdAt", entry.createdAtEpochMs); put("confirmations", entry.confirmations); put("fingerprint", entry.fingerprint); put("scope", entry.scope.name); put("ownerId", entry.ownerId ?: JSONObject.NULL); put("projectId", entry.projectId ?: JSONObject.NULL)
            put("tags", JSONArray(entry.tags)); put("retrievalHints", JSONArray(entry.retrievalHints)); put("source", entry.source?.let { JSONObject().put("type", it.type).put("uri", it.uri ?: JSONObject.NULL).put("providerId", it.providerId ?: JSONObject.NULL).put("modelId", it.modelId ?: JSONObject.NULL).put("repository", it.repository ?: JSONObject.NULL).put("path", it.path ?: JSONObject.NULL).put("commit", it.commit ?: JSONObject.NULL) } ?: JSONObject.NULL)
        }.toString() + "\n")
    }

    private fun load() {
        if (!file.exists()) return
        file.forEachLine { line -> runCatching { val j = JSONObject(line); val source = j.optJSONObject("source")?.let { KnowledgeSource(it.getString("type"), it.optString("uri").takeIf { value -> value.isNotBlank() }, it.optString("providerId").takeIf { value -> value.isNotBlank() }, it.optString("modelId").takeIf { value -> value.isNotBlank() }, it.optString("repository").takeIf { value -> value.isNotBlank() }, it.optString("path").takeIf { value -> value.isNotBlank() }, it.optString("commit").takeIf { value -> value.isNotBlank() }) }; val entry = KnowledgeEntry(j.getString("id"), j.getString("problem"), j.getString("answer"), source, jsonList(j.optJSONArray("retrievalHints")), jsonList(j.optJSONArray("tags")), j.optDouble("confidence", 0.0), j.optBoolean("validated"), j.optLong("createdAt"), j.optInt("confirmations"), fingerprint = j.optString("fingerprint").takeIf { value -> value.isNotBlank() } ?: fingerprintFor(j.getString("problem"), j.getString("answer")), scope = runCatching { KnowledgeScope.valueOf(j.optString("scope", "GLOBAL")) }.getOrDefault(KnowledgeScope.GLOBAL), ownerId = j.optString("ownerId").takeIf { value -> value.isNotBlank() }, projectId = j.optString("projectId").takeIf { value -> value.isNotBlank() }); entries[entry.id] = entry } }
    }
    private fun jsonList(array: JSONArray?): List<String> = array?.let { (0 until it.length()).map(it::getString) }.orEmpty()
    private fun similarity(a: String, b: String): Double { val x = a.lowercase().split(" ").toSet(); val y = b.lowercase().split(" ").toSet(); return if (x.isEmpty() || y.isEmpty()) 0.0 else 2.0 * x.intersect(y).size / (x.size + y.size) }
}
