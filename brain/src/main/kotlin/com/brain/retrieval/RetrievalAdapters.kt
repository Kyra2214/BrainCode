package com.brain.retrieval

import com.brain.memory.KnowledgeEntry
import com.brain.workflow.WorkflowManifest

object RetrievalAdapters {
    fun knowledge(entries: () -> Iterable<KnowledgeEntry>): RetrievalSource = RetrievalSource(
        id = "knowledge-memory",
        layer = RetrievalLayer.KNOWLEDGE,
        lookup = RetrievalLookup { query ->
            entries().asSequence()
                .filter { it.validated }
                .filter { entry ->
                    entry.problem.contains(query.objective, ignoreCase = true) ||
                        query.objective.contains(entry.problem, ignoreCase = true) ||
                        entry.retrievalHints.any { it.contains(query.objective, ignoreCase = true) }
                }
                .map { entry ->
                    RetrievalHit(
                        id = entry.id,
                        layer = RetrievalLayer.KNOWLEDGE,
                        content = entry.answer,
                        confidence = entry.confidence,
                        validated = true,
                        provenance = listOfNotNull(entry.source?.repository, entry.source?.commit).ifEmpty { listOf("knowledge:${entry.id}") }
                    )
                }
                .toList()
        }
    )

    fun workflows(manifests: () -> Iterable<WorkflowManifest>): RetrievalSource = RetrievalSource(
        id = "workflow-catalog",
        layer = RetrievalLayer.WORKFLOWS,
        validatedOnly = false,
        lookup = RetrievalLookup { query ->
            manifests().asSequence()
                .filter { it.enabled }
                .filter { manifest -> manifest.id.contains(query.objective, ignoreCase = true) || query.capability in manifest.nodes.map { it.capability } }
                .map { manifest ->
                    RetrievalHit(
                        id = manifest.id,
                        layer = RetrievalLayer.WORKFLOWS,
                        content = "workflow:${manifest.id}@${manifest.version}",
                        confidence = 0.7,
                        validated = true,
                        provenance = listOf("workflow:${manifest.id}")
                    )
                }
                .toList()
        }
    )
}
