package com.brain.conversation

import com.brain.router.PapelPipeline
import com.brain.secretary.DeterministicSecretary
import com.brain.secretary.Door
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ConversationContractsTest {
    @Test
    fun `advisor fake recebe classificação e devolve sugestão sem alterar intenção`() {
        val secretary = DeterministicSecretary()
        val tentativa = secretary.classify("quero conversar sobre um app")
        var receivedPrompt: String? = null
        var receivedDoor: Door? = null
        val fake = IntentAdvisor { prompt, classification ->
            receivedPrompt = prompt
            receivedDoor = classification.door
            OrderIntentSugerido(door = Door.CHAT, intent = "DISCUSSION", confidence = 0.98)
        }

        val suggestion = fake.revisarClassificacao("quero conversar sobre um app", tentativa)

        assertEquals("quero conversar sobre um app", receivedPrompt)
        assertEquals(tentativa.door, receivedDoor)
        assertEquals(Door.CHAT, suggestion.door)
        assertEquals("DISCUSSION", suggestion.intent)
    }

    @Test
    fun `interpreter fake produz estrutura determinística e independente de banco`() {
        val fake = ConversationInterpreter { prompt, context ->
            EstruturaExtraida(
                intent = "FACTUAL_QUESTION",
                normalizedQuery = prompt.trim().lowercase(),
                entities = mapOf("pais" to "Rússia"),
                parametrosParaAgente = mapOf("consulta" to prompt)
            )
        }

        val extracted = fake.extrairEstrutura(
            "Qual a capital da Rússia?",
            ConversationContext(requestId = "req-1")
        )

        assertEquals("FACTUAL_QUESTION", extracted.intent)
        assertEquals("qual a capital da rússia?", extracted.normalizedQuery)
        assertEquals("Rússia", extracted.entities["pais"])
        assertEquals("Qual a capital da Rússia?", extracted.parametrosParaAgente["consulta"])
    }

    @Test
    fun `reviewer fake retorna achados sem decidir aprovação`() {
        val fake = OutputReviewer { prompt, output ->
            RevisaoAchados(
                respondeAoPedido = output.contains("Macaé"),
                completo = prompt.isNotBlank() && output.isNotBlank(),
                observacoes = listOf("fake reviewer")
            )
        }

        val findings = fake.conferir("como está o tempo em Macaé?", "O tempo em Macaé está ensolarado.")

        assertTrue(findings.respondeAoPedido)
        assertTrue(findings.completo)
        assertEquals(listOf("fake reviewer"), findings.observacoes)
    }

    @Test
    fun `no-op implementations sao seguras para fallback offline`() {
        val intent = DeterministicSecretary().classify("olá")
        val suggestion = NoOpIntentAdvisor.revisarClassificacao("olá", intent)
        val extracted = NoOpConversationInterpreter.extrairEstrutura("  Olá   Brain ", ConversationContext())
        val findings = NoOpOutputReviewer.conferir("olá", "Olá!")

        assertEquals(intent.door, suggestion.door)
        assertEquals("olá brain", extracted.normalizedQuery)
        assertEquals("UNCLASSIFIED", extracted.intent)
        assertTrue(findings.respondeAoPedido)
    }

    @Test
    fun `contratos rejeitam estrutura vazia e confidence fora do intervalo`() {
        assertFailsWith<IllegalArgumentException> {
            EstruturaExtraida(intent = "", normalizedQuery = "consulta")
        }
        assertFailsWith<IllegalArgumentException> {
            EstruturaExtraida(intent = "FACT", normalizedQuery = "consulta", entities = emptyMap())
                .let { OrderIntentSugerido(Door.CHAT, confidence = 1.1) }
        }
    }

    @Test
    fun `pipeline possui papel conversacional sem remover papeis existentes`() {
        assertTrue(PapelPipeline.CONVERSACAO in PapelPipeline.values().toSet())
        assertTrue(PapelPipeline.PLANEJAMENTO in PapelPipeline.values().toSet())
        assertTrue(PapelPipeline.EXECUCAO_CODIGO in PapelPipeline.values().toSet())
    }
}
