package com.sandbox.app

import com.brain.conversation.BrainApiGateway
import com.brain.conversation.BrainCompletion
import com.brain.conversation.ConversationContext
import com.brain.router.PapelPipeline
import com.brain.secretary.Door
import com.brain.secretary.OrderIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LfmLocalBrainTest {
    private class FakeGateway(private val response: String) : BrainApiGateway {
        val pipelines = mutableListOf<PapelPipeline>()
        override fun complete(prompt: String, pipeline: PapelPipeline, authorizedAccountIds: Set<String>): BrainCompletion {
            pipelines += pipeline
            return BrainCompletion(response, "test-model", "free", "fake")
        }
    }

    @Test
    fun localIntentAdvisor_accepts_only_closed_door_and_bounded_confidence() {
        val gateway = FakeGateway("""{"door":"CHAT","confidence":0.91}""")
        val result = LfmIntentAdvisor(gateway).revisarClassificacao(
            "Como está o tempo?",
            OrderIntent("teste", Door.CHAT, com.brain.secretary.CreatePhase.CHAT)
        )
        assertEquals(Door.CHAT, result.door)
        assertEquals(0.91, result.confidence, 0.0)
        assertEquals(listOf(PapelPipeline.CONVERSACAO), gateway.pipelines)
    }

    @Test
    fun localIntentAdvisor_rejects_unknown_door() {
        val gateway = FakeGateway("""{"door":"EXECUTE_CODE","confidence":0.99}""")
        val result = runCatching {
            LfmIntentAdvisor(gateway).revisarClassificacao("execute isso", OrderIntent("execute isso", Door.CHAT, com.brain.secretary.CreatePhase.CHAT))
        }
        assertTrue(result.isFailure)
    }

    @Test
    fun localEntityInterpreter_keeps_only_literal_spans() {
        val gateway = FakeGateway("""{"entities":{"cidade":"Macaé","inventada":"São Paulo"}}""")
        val result = LfmEntityInterpreter(gateway).extrairEstrutura(
            "Qual o tempo em Macaé?",
            ConversationContext("test")
        )
        assertEquals("Macaé", result.entities["cidade"])
        assertTrue("inventada" !in result.entities)
        assertEquals(listOf(PapelPipeline.CONVERSACAO), gateway.pipelines)
    }
}
