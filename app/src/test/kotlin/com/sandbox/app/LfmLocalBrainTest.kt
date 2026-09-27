package com.sandbox.app

import com.brain.conversation.BrainApiGateway
import com.brain.conversation.BrainCompletion
import com.brain.conversation.ConversationContext
import com.brain.conversation.ConversationMetrics
import com.brain.conversation.HybridIntentAdvisor
import com.brain.conversation.IntentAdvisor
import com.brain.conversation.OrderIntentSugerido
import com.brain.router.PapelPipeline
import com.brain.secretary.Door
import com.brain.secretary.OrderIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    @Test
    fun localEntityInterpreter_skips_model_for_trivial_conversation() {
        val gateway = FakeGateway("""{"entities":{"cidade":"Macaé"}}""")
        val result = LfmEntityInterpreter(gateway).extrairEstrutura(
            "Olá",
            ConversationContext("test")
        )
        assertTrue(result.entities.isEmpty())
        assertTrue(gateway.pipelines.isEmpty())
    }

    @Test
    fun hybridAdvisor_treats_api_as_token_not_substring() {
        class CountingAdvisor(private val result: OrderIntentSugerido) : IntentAdvisor {
            var calls = 0
            override fun revisarClassificacao(prompt: String, classificacaoTentativa: OrderIntent): OrderIntentSugerido {
                calls++
                return result
            }
        }

        val local = CountingAdvisor(OrderIntentSugerido(Door.CHAT, 0.95))
        val cloud = CountingAdvisor(OrderIntentSugerido(Door.PROMPT, 0.99))
        val advisor = HybridIntentAdvisor(local, cloud)

        advisor.revisarClassificacao("capability", OrderIntent("capability", Door.CHAT, com.brain.secretary.CreatePhase.CHAT))
        assertEquals(1, local.calls)
        assertEquals(0, cloud.calls)

        advisor.revisarClassificacao("use the API", OrderIntent("use the API", Door.CHAT, com.brain.secretary.CreatePhase.CHAT))
        assertEquals(1, local.calls)
        assertEquals(1, cloud.calls)
    }

    @Test
    fun hybridAdvisor_records_cloud_failure_in_metrics() {
        val metrics = ConversationMetrics()
        val local = object : IntentAdvisor {
            override fun revisarClassificacao(prompt: String, classificacaoTentativa: OrderIntent): OrderIntentSugerido =
                OrderIntentSugerido(Door.CHAT, 0.5)
        }
        val cloud = object : IntentAdvisor {
            override fun revisarClassificacao(prompt: String, classificacaoTentativa: OrderIntent): OrderIntentSugerido =
                error("cloud unavailable")
        }

        HybridIntentAdvisor(local, cloud, metrics = metrics).revisarClassificacao(
            "use the API",
            OrderIntent("use the API", Door.CHAT, com.brain.secretary.CreatePhase.CHAT)
        )

        val snapshot = metrics.snapshot()
        assertEquals(1L, snapshot.llmCallsByHop["intent"])
        assertEquals(1L, snapshot.llmFailuresByHop["intent"])
        assertFalse(snapshot.llmFailuresByHop.isEmpty())
    }

    @Test
    fun localOnlyAdvisor_falls_back_deterministically_without_cloud() {
        val gateway = FakeGateway("""{"door":"EXECUTE_CODE","confidence":0.99}""")
        val result = LocalOnlyIntentAdvisor(gateway).revisarClassificacao(
            "execute isso",
            OrderIntent("execute isso", Door.CHAT, com.brain.secretary.CreatePhase.CHAT)
        )
        assertEquals(Door.CHAT, result.door)
        assertEquals(0.0, result.confidence, 0.0)
        assertEquals("local-fallback", result.rationale)
    }

    @Test
    fun localEntityInterpreter_does_not_trigger_on_generic_preposition() {
        val gateway = FakeGateway("""{"entities":{"cidade":"Macaé"}}""")
        val result = LfmEntityInterpreter(gateway).extrairEstrutura(
            "fale de uma coisa interessante para mim",
            ConversationContext("test")
        )
        assertTrue(result.entities.isEmpty())
        assertTrue(gateway.pipelines.isEmpty())
    }

    @Test
    fun nativeInferenceRunner_times_out_and_serializes_follow_up_calls() {
        val runner = LfmNativeInferenceRunner(timeoutMs = 40L)
        try {
            val timed = runCatching {
                runner.run {
                    Thread.sleep(200L)
                    "late"
                }
            }
            assertTrue(timed.isFailure)
            assertTrue(timed.exceptionOrNull()?.message?.contains("tempo limite") == true)

            val followUp = runner.run { "ok" }
            assertEquals("ok", followUp)
        } finally {
            runner.close()
        }
    }

}
