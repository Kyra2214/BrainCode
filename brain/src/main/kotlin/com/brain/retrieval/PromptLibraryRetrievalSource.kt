package com.brain.retrieval

import com.brain.prompt.PromptLibrary
import com.brain.prompt.PromptLibrarySnapshot

/** Adapta a biblioteca operacional de prompts ao retrieval antes de gerar um novo prompt. */
object PromptLibraryRetrievalSource {
    fun from(library: PromptLibrary): RetrievalSource = RetrievalSource(
        id = "prompt-library",
        layer = RetrievalLayer.PROMPT_LIBRARY,
        validatedOnly = false,
        lookup = RetrievalLookup { query ->
            val snapshot = library as? PromptLibrarySnapshot ?: return@RetrievalLookup emptyList()
            snapshot.buscarPorContextoSnapshot(query.objective).map { template ->
                RetrievalHit(
                    id = template.id,
                    layer = RetrievalLayer.PROMPT_LIBRARY,
                    content = template.textoTemplate,
                    // confidence representa relevância do retrieval; taxaSucesso é telemetria histórica.
                    // A busca da PromptLibrary já devolve os candidatos em ordem de relevância.
                    confidence = 0.5,
                    validated = true,
                    provenance = listOf(
                        "prompt-library:${template.id}",
                        "version:${template.versao}",
                        "historical-success:${"%.3f".format(java.util.Locale.US, template.taxaSucesso)}"
                    )
                )
            }
        }
    )
}
