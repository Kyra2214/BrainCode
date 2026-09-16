package com.sandbox.app

import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationContextEngineTest {
    private val engine = ConversationContextEngine()

    @Test
    fun `referencia melhora aponta para o prompt anterior`() {
        val history = listOf(
            ChatMessage(ChatRole.USER, "crie um prompt de foguete realista no espaço"),
            ChatMessage(ChatRole.ASSISTANT, "prompt: foguete hiper-realista orbitando Saturno, luz cinematográfica")
        )
        val resolved = engine.resolve(history, "melhore ele")
        assertTrue(resolved.objective.contains("prompt: foguete hiper-realista"))
    }

    @Test
    fun `contexto longo preserva ideia inicial e requisitos antigos`() {
        val history = buildList {
            add(ChatMessage(ChatRole.USER, "Tenho uma ideia de aplicativo offline para criar projetos"))
            add(ChatMessage(ChatRole.USER, "Ele precisa funcionar offline e o usuário poderá gerar projetos"))
            repeat(30) { add(ChatMessage(ChatRole.USER, "detalhe intermediário da implementação número $it")) }
        }
        val resolved = engine.resolve(history, "vamos implementar isso")
        assertTrue(resolved.objective.contains("aplicativo offline"))
        assertTrue(resolved.objective.contains("funcionar offline"))
    }

    @Test
    fun `decisão nova substitui decisão antiga e descarte não volta`() {
        val history = listOf(
            ChatMessage(ChatRole.USER, "vamos usar SQLite como armazenamento"),
            ChatMessage(ChatRole.USER, "mudamos de ideia, descartamos SQLite"),
            ChatMessage(ChatRole.USER, "vamos usar armazenamento em arquivo JSON")
        )
        val resolved = engine.resolve(history, "implementa isso")
        assertTrue(resolved.objective.contains("JSON"))
        assertTrue(resolved.objective.contains("não usar"))
        assertTrue(!resolved.context.decisions.any { it.contains("SQLite") })
    }
}
