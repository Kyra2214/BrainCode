package com.brain.memory

import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

data class KnowledgeSource(
    val type: String,
    val uri: String? = null,
    val providerId: String? = null,
    val modelId: String? = null,
    val repository: String? = null,
    val path: String? = null,
    val commit: String? = null
)

data class KnowledgeCitation(
    val uri: String,
    val quote: String,
    val retrievedAtEpochMs: Long = System.currentTimeMillis()
) { init { require(uri.isNotBlank() && quote.isNotBlank()) { "citação exige uri e quote" } } }

enum class KnowledgeScope { GLOBAL, USER, PROJECT }
enum class KnowledgeProvenance { LOCAL_BUILTIN, USER_PROVIDED, WEB_RESEARCH }

data class KnowledgeCorrection(
    val previousAnswer: String,
    val correctedAnswer: String,
    val confidence: Double,
    val correctedAtEpochMs: Long = System.currentTimeMillis()
)

data class KnowledgeEntry(
    val id: String = UUID.randomUUID().toString(),
    val problem: String,
    val answer: String,
    val source: KnowledgeSource?,
    val retrievalHints: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val confidence: Double = 0.0,
    val validated: Boolean = false,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    val confirmations: Int = 0,
    val citations: List<KnowledgeCitation> = emptyList(),
    val fingerprint: String = fingerprintFor(problem, answer),
    val scope: KnowledgeScope = KnowledgeScope.GLOBAL,
    val ownerId: String? = null,
    val projectId: String? = null,
    val correctionHistory: List<KnowledgeCorrection> = emptyList(),
    val expiresAtEpochMs: Long? = null,
    val provenance: KnowledgeProvenance = KnowledgeProvenance.LOCAL_BUILTIN
)

interface KnowledgeMemory {
    fun findValidated(problem: String, minScore: Double = 0.55, scope: KnowledgeScope = KnowledgeScope.GLOBAL, ownerId: String? = null, projectId: String? = null): KnowledgeEntry?
    fun saveCandidate(entry: KnowledgeEntry): KnowledgeEntry
    fun confirm(id: String, confidence: Double, source: KnowledgeSource? = null): KnowledgeEntry?
    fun recordCorrection(id: String, correctedAnswer: String, confidence: Double): KnowledgeEntry?
    fun all(): List<KnowledgeEntry>
}

class InMemoryKnowledgeMemory : KnowledgeMemory {
    private val entries = LinkedHashMap<String, KnowledgeEntry>()

    @Synchronized override fun findValidated(problem: String, minScore: Double, scope: KnowledgeScope, ownerId: String?, projectId: String?): KnowledgeEntry? =
        entries.values.asSequence()
            .filter { it.validated && it.scope == scope && (it.expiresAtEpochMs == null || it.expiresAtEpochMs > System.currentTimeMillis()) }
            .filter { scope != KnowledgeScope.USER || it.ownerId == ownerId }
            .filter { scope != KnowledgeScope.PROJECT || it.projectId == projectId }
            .map { it to similarity(problem, it.problem) }
            .filter { it.second >= minScore }
            .maxByOrNull { it.second }?.first

    @Synchronized override fun saveCandidate(entry: KnowledgeEntry): KnowledgeEntry {
        val duplicate = entries.values.firstOrNull {
            it.fingerprint == entry.fingerprint || similarity(it.problem, entry.problem) >= 0.75
        }
        if (duplicate != null) return duplicate
        entries[entry.id] = entry
        return entry
    }

    @Synchronized override fun confirm(id: String, confidence: Double, source: KnowledgeSource?): KnowledgeEntry? {
        val current = entries[id] ?: return null
        val updated = current.copy(confidence = confidence.coerceIn(0.0, 1.0), validated = true, source = source ?: current.source, confirmations = current.confirmations + 1)
        entries[id] = updated
        return updated
    }

    @Synchronized override fun recordCorrection(id: String, correctedAnswer: String, confidence: Double): KnowledgeEntry? {
        val current = entries[id] ?: return null
        val updated = current.copy(
            answer = correctedAnswer,
            confidence = confidence.coerceIn(0.0, 1.0),
            validated = true,
            confirmations = current.confirmations + 1,
            fingerprint = fingerprintFor(current.problem, correctedAnswer),
            correctionHistory = current.correctionHistory + KnowledgeCorrection(current.answer, correctedAnswer, confidence)
        )
        entries[id] = updated
        return updated
    }

    @Synchronized override fun all(): List<KnowledgeEntry> = entries.values.toList()

    private fun similarity(a: String, b: String): Double {
        val left = tokens(a); val right = tokens(b)
        if (left.isEmpty() || right.isEmpty()) return 0.0
        return (2.0 * left.intersect(right).size / (left.size + right.size)).coerceIn(0.0, 1.0)
    }
    private fun tokens(value: String): Set<String> = value.lowercase(Locale.ROOT)
        .replace(Regex("\\b(o que|me explica|explique|como funciona|fale sobre|fala sobre|sobre|qual|quais)\\b"), " ")
        .replace(Regex("[^\\p{L}\\p{N}_]+"), " ")
        .split(' ').filter { it.length >= 3 }.toSet()
}

fun fingerprintFor(problem: String, answer: String): String = MessageDigest.getInstance("SHA-256")
    .digest("${problem.trim().lowercase(Locale.ROOT)}\n${answer.trim()}".toByteArray())
    .joinToString("") { "%02x".format(it) }

object KnowledgeMemoryRegistry {
    @Volatile private var currentMemory: KnowledgeMemory = InMemoryKnowledgeMemory()
    private val conversationMemories = java.util.concurrent.ConcurrentHashMap<String, KnowledgeMemory>()
    fun current(): KnowledgeMemory = currentMemory
    fun install(memory: KnowledgeMemory) { currentMemory = memory }
    fun current(conversationId: String?): KnowledgeMemory = conversationId?.takeIf { it.isNotBlank() }?.let {
        conversationMemories.computeIfAbsent(it) { InMemoryKnowledgeMemory() }
    } ?: currentMemory
    fun install(memory: KnowledgeMemory, conversationId: String?) {
        if (conversationId.isNullOrBlank()) currentMemory = memory else conversationMemories[conversationId] = memory
    }
}
