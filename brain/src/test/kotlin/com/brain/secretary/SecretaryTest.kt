package com.brain.secretary

import com.brain.prompt.ImprovementVocabulary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SecretaryTest {
    private val secretary = DeterministicSecretary()

    @Test
    fun `corpus de classificacao atribui porta fase e restricoes`() {
        val cases = listOf(
            Case("Que dia é hoje?", Door.CHAT, CreatePhase.CHAT),
            Case("Estou pensando em criar um aplicativo", Door.CHAT, CreatePhase.CHAT),
            Case("Vamos discutir a arquitetura do produto", Door.CHAT, CreatePhase.CHAT),
            Case("Pesquise referências sobre Kotlin", Door.CHAT, CreatePhase.CHAT),
            Case("Não pesquise na internet, apenas explique", Door.CHAT, CreatePhase.CHAT, setOf(Restriction.NO_WEB)),
            Case("Sem criar nada, organize minhas ideias", Door.CHAT, CreatePhase.CHAT, setOf(Restriction.NO_PRODUCE)),
            Case("Não execute nada, quero só conversar", Door.CHAT, CreatePhase.CHAT, setOf(Restriction.NO_EXECUTE)),
            Case("Crie um prompt para uma imagem de um carro futurista", Door.PROMPT, CreatePhase.PROMPT),
            Case("Quero um prompt para resumir um artigo", Door.PROMPT, CreatePhase.PROMPT),
            Case("/prompt imagem cinematográfica", Door.PROMPT, CreatePhase.PROMPT),
            Case("Melhore este prompt sem criar projeto", Door.PROMPT, CreatePhase.PROMPT, setOf(Restriction.NO_PRODUCE)),
            Case("Quero criar um aplicativo de música", Door.CREATE, CreatePhase.DISCUSSION),
            Case("Desenvolva um site com login", Door.CREATE, CreatePhase.DISCUSSION),
            Case("Pode começar o desenvolvimento do aplicativo", Door.CREATE, CreatePhase.APPROVED),
            Case("Implemente e execute os testes do projeto", Door.CREATE, CreatePhase.APPROVED)
        )

        cases.forEach { expected ->
            val actual = secretary.classify(expected.prompt)
            assertEquals(expected.prompt, expected.door, actual.door)
            assertEquals(expected.prompt, expected.phase, actual.phase)
            assertEquals(expected.prompt, expected.restrictions, actual.restrictions)
            assertEquals(expected.door, actual.scope.door)
            assertEquals(expected.phase, actual.scope.phase)
            val esperadoExternalAccounts = when (expected.door) {
                Door.CHAT -> false
                Door.PROMPT -> ImprovementVocabulary.pedeIA(expected.prompt)
                Door.CREATE -> true
            }
            assertEquals(expected.prompt, esperadoExternalAccounts, actual.scope.externalAccountsAllowed)
        }
    }

    @Test
    fun `comando de catalogo entra sempre na porta de prompt`() {
        val intent = secretary.classify("/cinematic-prompt faça uma cena noturna")

        assertEquals(Door.PROMPT, intent.door)
        assertEquals(CreatePhase.PROMPT, intent.phase)
    }

    @Test
    fun `conversa sobre criar aplicativo nao abre porta de criacao`() {
        assertEquals(
            Door.CHAT,
            secretary.classify("quero apenas conversar sobre como criar um aplicativo de lista de compras. não quero criar o aplicativo agora").door
        )
        assertEquals(
            Door.CHAT,
            secretary.classify("tenho uma ideia de app, mas só quero trocar ideia por enquanto").door
        )
    }

    @Test
    fun `aprovacao explicita continua abrindo criacao na fase approved`() {
        val intent = secretary.classify("pode começar a desenvolver o app de lista de compras")
        assertEquals(Door.CREATE, intent.door)
        assertEquals(CreatePhase.APPROVED, intent.phase)
    }

    @Test
    fun `classificar rejeita prompt vazio`() {
        runCatching { secretary.classify(" ") }.onSuccess { error("prompt vazio deveria falhar") }
    }

    private data class Case(
        val prompt: String,
        val door: Door,
        val phase: CreatePhase,
        val restrictions: Set<Restriction> = emptySet()
    )
}
