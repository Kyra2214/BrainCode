package com.brain.conversation

import com.brain.secretary.Door
import com.brain.secretary.OrderIntent
import com.brain.secretary.CreatePhase
import org.junit.Assert.assertEquals
import org.junit.Test

class HybridIntentAdvisorTest {
    private val tentative = OrderIntent(
        originalPrompt = "quero discutir um app",
        door = Door.CHAT,
        phase = CreatePhase.CHAT,
        restrictions = emptySet(),
        scope = com.brain.secretary.DoorScope(Door.CHAT, CreatePhase.CHAT, emptySet(), false)
    )

    @Test
    fun `usa local quando a confiança passa o limiar`() {
        val local = IntentAdvisor { _, _ -> OrderIntentSugerido(Door.CREATE, confidence = 0.91) }
        val cloud = IntentAdvisor { _, _ -> OrderIntentSugerido(Door.CHAT, confidence = 0.99) }
        assertEquals(Door.CREATE, HybridIntentAdvisor(local, cloud).revisarClassificacao("quero discutir um app IPTV", tentative).door)
    }

    @Test
    fun `escala para cloud quando local tem baixa confiança`() {
        val local = IntentAdvisor { _, _ -> OrderIntentSugerido(Door.CREATE, confidence = 0.40) }
        val cloud = IntentAdvisor { _, _ -> OrderIntentSugerido(Door.CHAT, confidence = 0.99) }
        assertEquals(Door.CHAT, HybridIntentAdvisor(local, cloud).revisarClassificacao("quero discutir um app", tentative).door)
    }

    @Test
    fun `nao envia prompt complexo para local`() {
        var localCalls = 0
        val local = IntentAdvisor { _, _ -> localCalls++; OrderIntentSugerido(Door.CREATE, confidence = 1.0) }
        val cloud = IntentAdvisor { _, _ -> OrderIntentSugerido(Door.CHAT, confidence = 0.99) }
        assertEquals(Door.CHAT, HybridIntentAdvisor(local, cloud).revisarClassificacao("quero discutir API e codigo https://example.com", tentative).door)
        assertEquals(0, localCalls)
    }
}
