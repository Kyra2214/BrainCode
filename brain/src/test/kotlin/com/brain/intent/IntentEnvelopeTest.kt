package com.brain.intent

import com.brain.secretary.CreatePhase
import com.brain.capability.CapabilityCategory
import com.brain.capability.CapabilityDefinition
import com.brain.capability.CapabilityAvailability
import com.brain.capability.CapabilityProvenance
import com.brain.capability.CapabilityRegistry
import com.brain.secretary.DeterministicSecretary
import com.brain.secretary.Door
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IntentEnvelopeTest {
    private val interpreter = BrainInputInterpreter(DeterministicSecretary())

    @Test
    fun `matriz de rotas da porta 1`() {
        val cases = listOf(
            Case("Qual a temperatura de Rio das Ostras hoje?", Door.CHAT, IntentCategory.WEATHER, Route.CAPABILITY, "network.research"),
            Case("Quanto está fazendo em Rio das Ostras agora?", Door.CHAT, IntentCategory.WEATHER, Route.CAPABILITY, "network.research"),
            Case("Vai chover amanhã em Rio das Ostras?", Door.CHAT, IntentCategory.WEATHER, Route.CAPABILITY, "network.research"),
            Case("Abra o catálogo de capacidades.", Door.CHAT, IntentCategory.NAVIGATION, Route.CAPABILITY, "sandbox.info"),
            Case("Calcule 220V × 10A.", Door.CHAT, IntentCategory.CALCULATION, Route.CAPABILITY, "chat.respond"),
            Case("Quanto é 15% de 800?", Door.CHAT, IntentCategory.CALCULATION, Route.CAPABILITY, "chat.respond"),
            Case("Crie um plano para instalar um quadro.", Door.CREATE, IntentCategory.CREATION, Route.CREATION, "brain.plan"),
            Case("Explique como funciona um DR.", Door.CHAT, IntentCategory.INFORMATION, Route.CONVERSATION, null),
            Case("Pesquise no GitHub sobre X.", Door.CHAT, IntentCategory.RESEARCH, Route.CAPABILITY, "network.research"),
            Case("Me ajude a criar um aplicativo.", Door.CREATE, IntentCategory.CREATION, Route.CREATION, "brain.plan"),
            Case("Oi, tudo bem?", Door.CHAT, IntentCategory.CONVERSATION, Route.CONVERSATION, null),
            Case("Faça isso.", Door.CHAT, IntentCategory.AMBIGUOUS, Route.CLARIFY, null),
            Case("Procure o preço disso na internet.", Door.CHAT, IntentCategory.RESEARCH, Route.CAPABILITY, "network.research"),
            Case("Execute esse código.", Door.CHAT, IntentCategory.CODE_EXECUTION, Route.CAPABILITY, "sandbox.code"),
            Case("Quero apenas conversar sobre como criar um aplicativo; não quero criar agora", Door.CHAT, IntentCategory.CONVERSATION, Route.CONVERSATION, null),
            Case("Pode começar a desenvolver o app de lista de compras", Door.CREATE, IntentCategory.CREATION, Route.CREATION, "brain.plan", CreatePhase.APPROVED),
            Case("Tempo hoje em Rio das Ostras", Door.CHAT, IntentCategory.WEATHER, Route.CAPABILITY, "network.research")
            ,Case("Qual o melhor mecanismo pra criar um app de IPTV?", Door.CHAT, IntentCategory.INFORMATION, Route.CONVERSATION, null)
            ,Case("Como funciona um app de IPTV?", Door.CHAT, IntentCategory.INFORMATION, Route.CONVERSATION, null)
            ,Case("Quais tecnologias posso usar para fazer um app de IPTV?", Door.CHAT, IntentCategory.INFORMATION, Route.CONVERSATION, null)
            ,Case("Crie um app de IPTV", Door.CREATE, IntentCategory.CREATION, Route.CREATION, "brain.plan")
        )

        cases.forEach { expected ->
            val actual = interpreter.interpret(expected.text)
            assertEquals(expected.text, expected.door, actual.door)
            assertEquals(expected.text, expected.intent, actual.intent)
            assertEquals(expected.text, expected.route, actual.route)
            assertEquals(expected.text, expected.capability, actual.targetCapability)
            expected.phase?.let { phase ->
                assertEquals(expected.text, phase, DeterministicSecretary().classify(expected.text).phase)
            }
            assertTrue(expected.text, actual.confidence in 0.0..1.0)
            assertEquals(expected.text, EnvelopeSource.DETERMINISTIC, actual.source)
        }
    }

    @Test
    fun `envelope de clima extrai entidade e data sem acionar data`() {
        val envelope = interpreter.interpret("tempo hoje em rio das ostras")

        assertEquals(IntentCategory.WEATHER, envelope.intent)
        assertEquals(Route.CAPABILITY, envelope.route)
        assertEquals("network.research", envelope.targetCapability)
        assertEquals("rio das ostras", envelope.entities["location"])
        assertEquals("today", envelope.entities["date"])
    }

    @Test
    fun `operacoes aritmeticas nuas entram na porta de calculo local`() {
        listOf("7×5", "12+8", "100/4", "25-7", "10÷2").forEach { prompt ->
            val envelope = interpreter.interpret(prompt)
            assertEquals(prompt, IntentCategory.CALCULATION, envelope.intent)
            assertEquals(prompt, Route.CAPABILITY, envelope.route)
            assertEquals(prompt, "chat.respond", envelope.targetCapability)
        }
    }

    @Test
    fun `router resolve target capability somente quando registry a fornece`() {
        val definition = CapabilityDefinition(
            id = "sandbox.info",
            name = "sandbox.info",
            description = "test",
            category = CapabilityCategory.SANDBOX,
            ownerId = "test",
            origin = "test",
            providedCapabilities = setOf("network.research"),
            availability = CapabilityAvailability.AVAILABLE,
            provenance = listOf(CapabilityProvenance("test", "test"))
        )
        val registry = CapabilityRegistry(listOf(definition))
        val envelope = interpreter.interpret("Pesquise no GitHub sobre X.")

        assertEquals("network.research", BrainRouter().resolveCapability(envelope, registry))
        assertEquals(null, BrainRouter().resolveCapability(envelope.copy(targetCapability = "missing.capability"), registry))
    }

    @Test
    fun `fast path deterministico permanece abaixo de cinco segundos em mil classificacoes`() {
        val started = System.nanoTime()
        repeat(1_000) { interpreter.interpret("Oi, tudo bem?") }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        // O teste roda em runners compartilhados; mantém um teto de 5 ms por classificação
        // sem transformar a variabilidade do host em falso negativo de CI.
        assertTrue("fast path levou ${elapsedMs}ms", elapsedMs < 5_000)
    }

    private data class Case(
        val text: String,
        val door: Door,
        val intent: IntentCategory,
        val route: Route,
        val capability: String?,
        val phase: CreatePhase? = null
    )
}
