package com.brain.retrieval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RetrievalTest {
    private fun hit(id: String, layer: RetrievalLayer, validated: Boolean, confidence: Double = .9) = RetrievalHit(
        id = id,
        layer = layer,
        content = id,
        confidence = confidence,
        validated = validated,
        provenance = listOf("test:$id")
    )

    @Test
    fun `memory validada encerra antes de skill prompt tool agent e api`() {
        val calls = mutableListOf<String>()
        fun source(id: String, layer: RetrievalLayer, result: List<RetrievalHit>) = RetrievalSource(
            id = id,
            layer = layer,
            lookup = RetrievalLookup { calls += id; result }
        )
        val retrieval = Retrieval(
            listOf(
                source("api", RetrievalLayer.APIS, listOf(hit("api", RetrievalLayer.APIS, true))),
                source("memory", RetrievalLayer.MEMORY, listOf(hit("memory", RetrievalLayer.MEMORY, true))),
                source("skill", RetrievalLayer.SKILLS, listOf(hit("skill", RetrievalLayer.SKILLS, true))),
                source("prompt", RetrievalLayer.PROMPT_LIBRARY, listOf(hit("prompt", RetrievalLayer.PROMPT_LIBRARY, true))),
                source("tool", RetrievalLayer.TOOLS, listOf(hit("tool", RetrievalLayer.TOOLS, true))),
                source("agent", RetrievalLayer.AGENTS, listOf(hit("agent", RetrievalLayer.AGENTS, true)))
            )
        )

        val result = retrieval.retrieve(RetrievalQuery("como fazer X"))

        assertEquals(RetrievalStatus.FOUND, result.status)
        assertEquals("memory", result.hit?.id)
        assertEquals(listOf("memory"), calls)
        assertTrue(RetrievalLayer.APIS !in result.attemptedLayers)
    }

    @Test
    fun `hit de memoria nao validado nao interrompe busca e skill validada vence api`() {
        val calls = mutableListOf<String>()
        val retrieval = Retrieval(
            listOf(
                RetrievalSource(
                    id = "memory", layer = RetrievalLayer.MEMORY,
                    lookup = RetrievalLookup { calls += "memory"; listOf(hit("candidate", RetrievalLayer.MEMORY, false)) }
                ),
                RetrievalSource(
                    id = "skill", layer = RetrievalLayer.SKILLS,
                    lookup = RetrievalLookup { calls += "skill"; listOf(hit("skill", RetrievalLayer.SKILLS, true, .7)) }
                ),
                RetrievalSource(
                    id = "api", layer = RetrievalLayer.APIS,
                    lookup = RetrievalLookup { calls += "api"; listOf(hit("api", RetrievalLayer.APIS, true, 1.0)) }
                )
            )
        )

        val result = retrieval.retrieve(RetrievalQuery("procedimento repetível"))

        assertEquals("skill", result.hit?.id)
        assertEquals(listOf("memory", "skill"), calls)
    }

    @Test
    fun `resultado not found registra todas as fontes em ordem`() {
        val retrieval = Retrieval(
            listOf(
                RetrievalSource("api", RetrievalLayer.APIS, RetrievalLookup { emptyList() }),
                RetrievalSource("memory", RetrievalLayer.MEMORY, RetrievalLookup { emptyList() })
            )
        )

        val result = retrieval.retrieve(RetrievalQuery("desconhecido"))

        assertEquals(RetrievalStatus.NOT_FOUND, result.status)
        assertEquals(listOf("memory", "api"), result.attemptedSources)
    }
}
