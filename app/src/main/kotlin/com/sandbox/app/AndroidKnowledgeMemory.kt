package com.sandbox.app

import android.content.Context
import com.brain.memory.KnowledgeEntry
import com.brain.memory.KnowledgeMemory
import com.brain.memory.KnowledgeSource
import com.brain.memory.KnowledgeScope
import com.brain.memory.KnowledgeCorrection
import com.brain.memory.fingerprintFor
import org.json.JSONArray
import org.json.JSONObject

/** Memória persistente do Brain; não armazena chaves de API nem prompts secretos. */
class AndroidKnowledgeMemory(context: Context) : KnowledgeMemory {
    private val prefs = context.applicationContext.getSharedPreferences("brain_knowledge", Context.MODE_PRIVATE)
    private val lock = Any()

    override fun findValidated(problem: String, minScore: Double, scope: KnowledgeScope, ownerId: String?, projectId: String?): KnowledgeEntry? = synchronized(lock) {
        load().asSequence()
            .filter { it.validated && it.scope == scope }
            .filter { scope != KnowledgeScope.USER || it.ownerId == ownerId }
            .filter { scope != KnowledgeScope.PROJECT || it.projectId == projectId }
            .map { it to similarity(problem, it.problem) }
            .filter { it.second >= minScore }
            .maxByOrNull { it.second }?.first
    }

    override fun saveCandidate(entry: KnowledgeEntry): KnowledgeEntry = synchronized(lock) {
        val entries = load().associateBy { it.id }.toMutableMap()
        if (entries.values.none { it.fingerprint == entry.fingerprint }) entries[entry.id] = entry
        save(entries.values.toList())
        entry
    }

    override fun confirm(id: String, confidence: Double, source: KnowledgeSource?): KnowledgeEntry? = synchronized(lock) {
        val current = load().firstOrNull { it.id == id } ?: return@synchronized null
        val updated = current.copy(
            confidence = confidence.coerceIn(0.0, 1.0),
            validated = true,
            source = source ?: current.source,
            confirmations = current.confirmations + 1
        )
        save(load().map { if (it.id == id) updated else it })
        updated
    }

    override fun recordCorrection(id: String, correctedAnswer: String, confidence: Double): KnowledgeEntry? = synchronized(lock) {
        val current = load().firstOrNull { it.id == id } ?: return@synchronized null
        val updated = current.copy(
            answer = correctedAnswer,
            confidence = confidence.coerceIn(0.0, 1.0),
            validated = true,
            confirmations = current.confirmations + 1,
            fingerprint = fingerprintFor(current.problem, correctedAnswer),
            correctionHistory = current.correctionHistory + KnowledgeCorrection(current.answer, correctedAnswer, confidence)
        )
        save(load().map { if (it.id == id) updated else it })
        updated
    }

    override fun all(): List<KnowledgeEntry> = synchronized(lock) { load() }

    private fun load(): List<KnowledgeEntry> = runCatching {
        val array = JSONArray(prefs.getString(KEY, "[]"))
        buildList {
            for (i in 0 until array.length()) add(fromJson(array.getJSONObject(i)))
        }
    }.getOrDefault(emptyList())

    private fun save(entries: List<KnowledgeEntry>) {
        val array = JSONArray()
        entries.forEach { array.put(toJson(it)) }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    private fun toJson(entry: KnowledgeEntry): JSONObject = JSONObject().apply {
        put("id", entry.id)
        put("problem", entry.problem)
        put("answer", entry.answer)
        put("confidence", entry.confidence)
        put("validated", entry.validated)
        put("createdAtEpochMs", entry.createdAtEpochMs)
        put("confirmations", entry.confirmations)
        put("fingerprint", entry.fingerprint)
        put("scope", entry.scope.name)
        entry.ownerId?.let { put("ownerId", it) }
        entry.projectId?.let { put("projectId", it) }
        put("retrievalHints", JSONArray(entry.retrievalHints))
        put("tags", JSONArray(entry.tags))
        entry.source?.let { source ->
            put("source", JSONObject().apply {
                put("type", source.type)
                source.uri?.let { put("uri", it) }
                source.providerId?.let { put("providerId", it) }
                source.modelId?.let { put("modelId", it) }
                source.repository?.let { put("repository", it) }
                source.path?.let { put("path", it) }
                source.commit?.let { put("commit", it) }
            })
        }
    }

    private fun fromJson(json: JSONObject): KnowledgeEntry = KnowledgeEntry(
        id = json.getString("id"),
        problem = json.getString("problem"),
        answer = json.getString("answer"),
        source = json.optJSONObject("source")?.let {
            KnowledgeSource(
                type = it.optString("type", "unknown"),
                uri = it.optString("uri").takeIf { value -> value.isNotBlank() },
                providerId = it.optString("providerId").takeIf { value -> value.isNotBlank() },
                modelId = it.optString("modelId").takeIf { value -> value.isNotBlank() },
                repository = it.optString("repository").takeIf { value -> value.isNotBlank() },
                path = it.optString("path").takeIf { value -> value.isNotBlank() },
                commit = it.optString("commit").takeIf { value -> value.isNotBlank() }
            )
        },
        retrievalHints = json.optJSONArray("retrievalHints").toStringList(),
        tags = json.optJSONArray("tags").toStringList(),
        confidence = json.optDouble("confidence", 0.0),
        validated = json.optBoolean("validated", false),
        createdAtEpochMs = json.optLong("createdAtEpochMs", 0L),
        confirmations = json.optInt("confirmations", 0),
        fingerprint = json.optString("fingerprint").takeIf { it.isNotBlank() } ?: fingerprintFor(json.getString("problem"), json.getString("answer")),
        scope = runCatching { KnowledgeScope.valueOf(json.optString("scope", "GLOBAL")) }.getOrDefault(KnowledgeScope.GLOBAL),
        ownerId = json.optString("ownerId").takeIf { it.isNotBlank() },
        projectId = json.optString("projectId").takeIf { it.isNotBlank() }
    )

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return buildList { for (i in 0 until length()) add(optString(i)) }
    }

    private fun similarity(a: String, b: String): Double {
        val left = a.lowercase().split(Regex("[^\\p{L}\\p{N}_]+" )).filter { it.length >= 3 }.toSet()
        val right = b.lowercase().split(Regex("[^\\p{L}\\p{N}_]+" )).filter { it.length >= 3 }.toSet()
        if (left.isEmpty() || right.isEmpty()) return 0.0
        return (2.0 * left.intersect(right).size / (left.size + right.size)).coerceIn(0.0, 1.0)
    }

    private companion object { const val KEY = "entries" }
}
