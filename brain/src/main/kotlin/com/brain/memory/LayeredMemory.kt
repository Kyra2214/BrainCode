package com.brain.memory

import com.brain.research.ResearchResult
import java.time.Instant

/** Proveniência mínima para qualquer fato persistido pelo Brain. */
data class Provenance(
    val source: String,
    val recordedAt: Instant = Instant.now(),
    val confidence: Double = 0.0
)

data class MemoryEntry<T>(
    val value: T,
    val provenance: Provenance,
    val validated: Boolean = false
)

/** Memória explícita por camada; uma evidência não vira verdade sem validação. */
class LayeredMemory {
    private val episodic = mutableListOf<MemoryEntry<String>>()
    private val semantic = mutableListOf<MemoryEntry<String>>()
    private val procedural = mutableListOf<MemoryEntry<String>>()
    private val evidence = mutableListOf<MemoryEntry<ResearchResult>>()

    @Synchronized fun rememberEpisode(value: String, provenance: Provenance) { episodic += MemoryEntry(value, provenance) }
    @Synchronized fun rememberFact(value: String, provenance: Provenance, validated: Boolean = false) { semantic += MemoryEntry(value, provenance, validated) }
    @Synchronized fun rememberProcedure(value: String, provenance: Provenance, validated: Boolean = false) { procedural += MemoryEntry(value, provenance, validated) }
    @Synchronized fun rememberEvidence(value: ResearchResult, provenance: Provenance, validated: Boolean = false) { evidence += MemoryEntry(value, provenance, validated) }

    @Synchronized fun episodes(): List<MemoryEntry<String>> = episodic.toList()
    @Synchronized fun facts(): List<MemoryEntry<String>> = semantic.toList()
    @Synchronized fun procedures(): List<MemoryEntry<String>> = procedural.toList()
    @Synchronized fun evidences(): List<MemoryEntry<ResearchResult>> = evidence.toList()

    @Synchronized fun validateEvidence(url: String): Boolean {
        var changed = false
        for (index in evidence.indices) {
            val item = evidence[index]
            if (item.value.url == url) {
                evidence[index] = item.copy(validated = true)
                changed = true
            }
        }
        return changed
    }
}
