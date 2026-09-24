package com.brain.conversation

import com.brain.memory.InMemoryKnowledgeMemory
import com.brain.memory.KnowledgeEntry
import com.brain.router.PapelPipeline
import com.brain.secretary.Door
import com.brain.secretary.OrderIntent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LlmConversationAdaptersTest {
    private class FakeGateway(private val response: String) : BrainApiGateway {
        val pipelines = mutableListOf<PapelPipeline>()
        override fun complete(prompt: String, pipeline: PapelPipeline, authorizedAccountIds: Set<String>): BrainCompletion {
            pipelines += pipeline
            return BrainCompletion(response, "model-test", "free", "fake")
        }
    }

    @Test
    fun `adapters parseiam contratos JSON e preservam pipeline`() {
        val gateway = FakeGateway("""{"intent":"WEATHER","normalizedQuery":"tempo macae","entities":{"cidade":"Macaé"},"parameters":{"consulta":"tempo"}}""")
        val result = LlmConversationInterpreter(gateway).extrairEstrutura("como está o tempo em Macaé?", ConversationContext("r1"))
        assertEquals("WEATHER", result.intent)
        assertEquals("Macaé", result.entities["cidade"])
        assertEquals(listOf(PapelPipeline.CONVERSACAO), gateway.pipelines)
    }

    @Test
    fun `resposta inválida usa fallback determinístico por hop`() {
        val gateway = FakeGateway("não é json")
        val result = LlmConversationInterpreter(gateway).extrairEstrutura("Olá", ConversationContext())
        assertEquals("UNCLASSIFIED", result.intent)
        assertEquals("olá", result.normalizedQuery)
    }

    @Test
    fun `fluxo consulta texto e depois estrutura somente em miss`() {
        val memory = InMemoryKnowledgeMemory()
        val entry = memory.saveCandidate(KnowledgeEntry("id", "qual a capital da Rússia?", "Moscou", null, validated = true, intent = "FACT", entities = mapOf("pais" to "Rússia")))
        var interpreted = 0
        val flow = ConversationKnowledgeFlow(memory, ConversationInterpreter { _, _ -> interpreted++; EstruturaExtraida("FACT", "capital russia", mapOf("pais" to "russa")) })
        val hit = flow.recall("qual cidade governa o país eslavo?")
        assertNotNull(hit.entry)
        assertEquals(entry.id, hit.entry!!.id)
        assertEquals(1, interpreted)
        assertEquals("layer1-structured", hit.layer)
    }

    @Test
    fun `advisor real cai para fallback em resposta inválida`() {
        val gateway = FakeGateway("{}")
        val tentative = OrderIntent("quero conversar", Door.CHAT, com.brain.secretary.CreatePhase.CHAT, emptySet(), com.brain.secretary.DoorScope(Door.CHAT, com.brain.secretary.CreatePhase.CHAT, emptySet(), false))
        assertEquals(Door.CHAT, LlmIntentAdvisor(gateway).revisarClassificacao("quero conversar", tentative).door)
        assertTrue(gateway.pipelines.contains(PapelPipeline.CONVERSACAO))
    }
}
