package com.sandbox.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.brain.memory.KnowledgeCitation
import com.brain.memory.KnowledgeCorrection
import com.brain.memory.KnowledgeEntry
import com.brain.memory.KnowledgeMemory
import com.brain.memory.KnowledgeProvenance
import com.brain.memory.KnowledgeScope
import com.brain.memory.KnowledgeSource
import com.brain.memory.ValidationStatus
import com.brain.memory.fingerprintFor
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale

/** Memória persistente SQL do Secretário; a LLM nunca acessa esta classe diretamente. */
class SqlKnowledgeMemory(context: Context) : KnowledgeMemory {
    private val helper = BrainKnowledgeDb(context.applicationContext)
    private val lock = Any()

    init {
        synchronized(lock) { migrateLegacyPreferencesIfNeeded() }
    }

    override fun findValidated(problem: String, minScore: Double, scope: KnowledgeScope, ownerId: String?, projectId: String?): KnowledgeEntry? =
        synchronized(lock) {
            queryCandidates(scope, ownerId, projectId)
                .map { it to similarity(problem, it.problem) }
                .filter { it.second >= minScore }
                .maxByOrNull { it.second }?.first
        }

    override fun findValidatedStructured(
        problem: String,
        intent: String,
        entities: Map<String, String>,
        minScore: Double,
        scope: KnowledgeScope,
        ownerId: String?,
        projectId: String?
    ): KnowledgeEntry? = synchronized(lock) {
        val structured = queryByIntent(intent, scope, ownerId, projectId)
            .map { it to entityOverlapScore(it.entities, entities) }
            .filter { it.second >= 0.5 }
            .maxByOrNull { it.second }?.first
        structured ?: findValidated(problem, minScore, scope, ownerId, projectId)
    }

    override fun saveCandidate(entry: KnowledgeEntry): KnowledgeEntry = synchronized(lock) {
        val db = helper.writableDatabase
        val existing = db.query(TABLE, arrayOf(COL_ID), "$COL_FINGERPRINT = ?", arrayOf(entry.fingerprint), null, null, null, "1")
        val duplicate = existing.use { it.moveToFirst() }
        if (!duplicate) db.insertOrThrow(TABLE, null, values(entry))
        entry
    }

    override fun confirm(id: String, confidence: Double, source: KnowledgeSource?): KnowledgeEntry? = synchronized(lock) {
        val current = findById(id) ?: return@synchronized null
        val updated = current.copy(
            confidence = confidence.coerceIn(0.0, 1.0),
            validated = true,
            validationStatus = ValidationStatus.VALIDATED,
            source = source ?: current.source,
            confirmations = current.confirmations + 1
        )
        replace(updated)
        updated
    }

    override fun recordCorrection(id: String, correctedAnswer: String, confidence: Double): KnowledgeEntry? = synchronized(lock) {
        val current = findById(id) ?: return@synchronized null
        val updated = current.copy(
            answer = correctedAnswer,
            confidence = confidence.coerceIn(0.0, 1.0),
            validated = true,
            validationStatus = ValidationStatus.CORRECTED,
            confirmations = current.confirmations + 1,
            fingerprint = fingerprintFor(current.problem, correctedAnswer),
            correctionHistory = current.correctionHistory + KnowledgeCorrection(current.answer, correctedAnswer, confidence),
            version = current.version + 1
        )
        replace(updated)
        updated
    }

    override fun all(): List<KnowledgeEntry> = synchronized(lock) {
        queryAll().map { fromJson(JSONObject(it)) }
    }

    private fun queryByIntent(intent: String, scope: KnowledgeScope, ownerId: String?, projectId: String?): List<KnowledgeEntry> =
        queryRows("$COL_INTENT = ?", arrayOf(intent), scope, ownerId, projectId)

    private fun queryCandidates(scope: KnowledgeScope, ownerId: String?, projectId: String?): List<KnowledgeEntry> =
        queryRows(null, emptyArray(), scope, ownerId, projectId)

    private fun queryRows(where: String?, args: Array<String>, scope: KnowledgeScope, ownerId: String?, projectId: String?): List<KnowledgeEntry> {
        val clauses = mutableListOf<String>()
        val values = args.toMutableList()
        where?.let { clauses += it }
        clauses += "$COL_VALIDATED = 1"
        clauses += "$COL_SCOPE = ?"; values += scope.name
        if (scope == KnowledgeScope.USER) { clauses += "$COL_OWNER = ?"; values += (ownerId ?: "") }
        if (scope == KnowledgeScope.PROJECT) { clauses += "$COL_PROJECT = ?"; values += (projectId ?: "") }
        val now = System.currentTimeMillis()
        clauses += "($COL_EXPIRES IS NULL OR $COL_EXPIRES > ?)"; values += now.toString()
        return helper.readableDatabase.query(TABLE, arrayOf(COL_PAYLOAD), clauses.joinToString(" AND "), values.toTypedArray(), null, null, null)
            .use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
            .map { fromJson(JSONObject(it)) }
            .filter { it.validationStatus != ValidationStatus.EXPIRED }
    }

    private fun findById(id: String): KnowledgeEntry? = helper.readableDatabase.query(TABLE, arrayOf(COL_PAYLOAD), "$COL_ID = ?", arrayOf(id), null, null, null, "1")
        .use { if (it.moveToFirst()) fromJson(JSONObject(it.getString(0))) else null }

    private fun replace(entry: KnowledgeEntry) {
        helper.writableDatabase.update(TABLE, values(entry), "$COL_ID = ?", arrayOf(entry.id))
    }

    private fun queryAll(): List<String> = helper.readableDatabase.query(TABLE, arrayOf(COL_PAYLOAD), null, null, null, null, null)
        .use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }

    private fun values(entry: KnowledgeEntry) = ContentValues().apply {
        put(COL_ID, entry.id); put(COL_INTENT, entry.intent); put(COL_ENTITY_FP, entityFingerprint(entry.entities))
        put(COL_FINGERPRINT, entry.fingerprint); put(COL_SCOPE, entry.scope.name); put(COL_OWNER, entry.ownerId)
        put(COL_PROJECT, entry.projectId); put(COL_VALIDATED, if (entry.validated) 1 else 0); put(COL_EXPIRES, entry.expiresAtEpochMs)
        put(COL_PAYLOAD, toJson(entry).toString())
    }

    private fun migrateLegacyPreferencesIfNeeded() {
        val db = helper.writableDatabase
        if (db.rawQuery("SELECT COUNT(*) FROM $TABLE", null).use { it.moveToFirst() && it.getInt(0) > 0 }) return
        val prefs = helper.context.getSharedPreferences("brain_knowledge", Context.MODE_PRIVATE)
        val array = runCatching { JSONArray(prefs.getString("entries", "[]")) }.getOrDefault(JSONArray())
        for (i in 0 until array.length()) {
            val legacy = array.getJSONObject(i)
            val entry = fromJson(legacy)
            db.insertWithOnConflict(TABLE, null, values(entry), SQLiteDatabase.CONFLICT_IGNORE)
        }
        prefs.edit().putBoolean("sql_migrated", true).apply()
    }

    private fun toJson(entry: KnowledgeEntry): JSONObject = JSONObject().apply {
        put("id", entry.id); put("problem", entry.problem); put("answer", entry.answer); put("confidence", entry.confidence)
        put("validated", entry.validated); put("validationStatus", entry.validationStatus.name); put("createdAtEpochMs", entry.createdAtEpochMs)
        put("confirmations", entry.confirmations); put("fingerprint", entry.fingerprint); put("scope", entry.scope.name)
        put("version", entry.version); put("intent", entry.intent); put("normalizedQuery", entry.normalizedQuery)
        put("entities", JSONObject(entry.entities as Map<*, *>)); put("capabilities", JSONArray(entry.capabilities)); put("retrievalHints", JSONArray(entry.retrievalHints)); put("tags", JSONArray(entry.tags))
        put("expiresAtEpochMs", entry.expiresAtEpochMs); put("provenance", entry.provenance.name); put("citations", JSONArray(entry.citations.map { JSONObject().put("uri", it.uri).put("quote", it.quote).put("retrievedAtEpochMs", it.retrievedAtEpochMs) }))
        entry.ownerId?.let { put("ownerId", it) }; entry.projectId?.let { put("projectId", it) }
        entry.source?.let { put("source", JSONObject().put("type", it.type).put("uri", it.uri).put("providerId", it.providerId).put("modelId", it.modelId).put("repository", it.repository).put("path", it.path).put("commit", it.commit)) }
    }

    private fun fromJson(json: JSONObject): KnowledgeEntry {
        val problem = json.getString("problem"); val answer = json.getString("answer")
        return KnowledgeEntry(
            id = json.optString("id"), problem = problem, answer = answer,
            source = json.optJSONObject("source")?.let { KnowledgeSource(it.optString("type", "unknown"), it.optString("uri").takeIf(String::isNotBlank), it.optString("providerId").takeIf(String::isNotBlank), it.optString("modelId").takeIf(String::isNotBlank), it.optString("repository").takeIf(String::isNotBlank), it.optString("path").takeIf(String::isNotBlank), it.optString("commit").takeIf(String::isNotBlank)) },
            retrievalHints = json.optJSONArray("retrievalHints").strings(), tags = json.optJSONArray("tags").strings(), confidence = json.optDouble("confidence"), validated = json.optBoolean("validated"), createdAtEpochMs = json.optLong("createdAtEpochMs"), confirmations = json.optInt("confirmations"), fingerprint = json.optString("fingerprint").takeIf(String::isNotBlank) ?: fingerprintFor(problem, answer), scope = runCatching { KnowledgeScope.valueOf(json.optString("scope", "GLOBAL")) }.getOrDefault(KnowledgeScope.GLOBAL), ownerId = json.optString("ownerId").takeIf(String::isNotBlank), projectId = json.optString("projectId").takeIf(String::isNotBlank), citations = json.optJSONArray("citations").citations(), expiresAtEpochMs = json.optLong("expiresAtEpochMs").takeIf { it > 0 }, provenance = runCatching { KnowledgeProvenance.valueOf(json.optString("provenance", "LOCAL_BUILTIN")) }.getOrDefault(KnowledgeProvenance.LOCAL_BUILTIN), intent = json.optString("intent").takeIf(String::isNotBlank), normalizedQuery = json.optString("normalizedQuery").takeIf(String::isNotBlank), entities = json.optJSONObject("entities").map(), capabilities = json.optJSONArray("capabilities").strings(), validationStatus = runCatching { ValidationStatus.valueOf(json.optString("validationStatus")) }.getOrElse { if (json.optBoolean("validated")) ValidationStatus.VALIDATED else ValidationStatus.PENDING }, version = json.optInt("version", 1)
        )
    }

    private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else buildList { for (i in 0 until length()) add(optString(i)) }
    private fun JSONObject?.map(): Map<String, String> = if (this == null) emptyMap() else keys().asSequence().associateWith { optString(it) }
    private fun JSONArray?.citations(): List<KnowledgeCitation> = if (this == null) emptyList() else buildList { for (i in 0 until length()) optJSONObject(i)?.let { add(KnowledgeCitation(it.getString("uri"), it.getString("quote"), it.optLong("retrievedAtEpochMs"))) } }

    private fun entityFingerprint(entities: Map<String, String>): String = entities.toSortedMap().entries.joinToString("|") { "${it.key}=${normalize(it.value)}" }
    private fun entityOverlapScore(left: Map<String, String>, right: Map<String, String>): Double = if (left.isEmpty() || right.isEmpty()) 0.0 else left.entries.count { right[it.key]?.let { value -> normalize(value) == normalize(it.value) } == true }.toDouble() / maxOf(left.size, right.size)
    private fun similarity(a: String, b: String): Double { val l = tokens(a); val r = tokens(b); return if (l.isEmpty() || r.isEmpty()) 0.0 else 2.0 * l.intersect(r).size / (l.size + r.size) }
    private fun tokens(value: String): Set<String> = value.lowercase(Locale.ROOT).split(Regex("[^\\p{L}\\p{N}_]+" )).filter { it.length >= 3 }.map(::normalize).toSet()
    private fun normalize(value: String): String = Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").replace(Regex("s$"), "")

    private class BrainKnowledgeDb(val context: Context) : SQLiteOpenHelper(context, "brain_knowledge.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) { db.execSQL("CREATE TABLE $TABLE (id TEXT PRIMARY KEY, intent TEXT, entityFingerprint TEXT, fingerprint TEXT, scope TEXT NOT NULL, ownerId TEXT, projectId TEXT, validated INTEGER NOT NULL, expiresAtEpochMs INTEGER, payload TEXT NOT NULL)"); db.execSQL("CREATE INDEX idx_brain_knowledge_intent ON $TABLE(intent)"); db.execSQL("CREATE INDEX idx_brain_knowledge_entities ON $TABLE(entityFingerprint)") }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    private companion object { const val TABLE = "knowledge_entries"; const val COL_ID = "id"; const val COL_INTENT = "intent"; const val COL_ENTITY_FP = "entityFingerprint"; const val COL_FINGERPRINT = "fingerprint"; const val COL_SCOPE = "scope"; const val COL_OWNER = "ownerId"; const val COL_PROJECT = "projectId"; const val COL_VALIDATED = "validated"; const val COL_EXPIRES = "expiresAtEpochMs"; const val COL_PAYLOAD = "payload" }
}
