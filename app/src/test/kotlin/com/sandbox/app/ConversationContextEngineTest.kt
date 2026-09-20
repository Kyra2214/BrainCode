package com.sandbox.app

import org.junit.Assert.assertFalse
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
    fun `melhorar e mudar recuperam o artefato anterior`() {
        val history = listOf(
            ChatMessage(ChatRole.USER, "crie um prompt de uma casa na montanha"),
            ChatMessage(ChatRole.ASSISTANT, "Prompt final: casa moderna na montanha ao amanhecer")
        )
        val melhorar = engine.resolve(history, "vamos melhorar o prompt")
        val mudar = engine.resolve(history, "vamos mudar o prompt para uma casa de madeira")
        assertTrue(melhorar.context.references.any { it.contains("artefato anterior") })
        assertTrue(mudar.context.references.any { it.contains("artefato anterior") })
    }

    @Test
    fun `melhorar foguete preserva detalhes novos e referencia o artefato anterior`() {
        val promptAnterior = "Fotografia fotorrealista de um foguete espacial decolando, com iluminação cinematográfica, céu estrelado ao fundo, alta definição e detalhes realistas."
        val history = listOf(
            ChatMessage(ChatRole.USER, "crie um prompt de imagem realista de um foguete"),
            ChatMessage(ChatRole.ASSISTANT, promptAnterior)
        )

        val resolved = engine.resolve(
            history,
            "vamos melhorar quero esse foguete no deserto ao entardecer a imagem e de uma plataforma ao longe"
        )

        assertTrue(resolved.objective.contains("deserto"))
        assertTrue(resolved.objective.contains("entardecer"))
        assertTrue(resolved.objective.contains("plataforma ao longe"))
        assertTrue(resolved.objective.contains("artefato anterior: $promptAnterior"))
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

    @Test
    fun `pedido novo nao herda artefato anterior sem referencia de ajuste`() {
        val history = listOf(
            ChatMessage(ChatRole.USER, "crie um prompt para uma interface de chatbox"),
            ChatMessage(ChatRole.ASSISTANT, "Fotografia de uma interface de chatbox, ambientado em um estúdio.")
        )
        val novoPedido = "crie um prompt para um foguete espacial decolando no deserto"

        val resolved = engine.resolve(history, novoPedido)

        assertTrue(resolved.objective == novoPedido)
        assertTrue(resolved.context.references.isEmpty())
    }

    @Test
    fun `pedido independente nao herda ideia requisitos ou decisoes da conversa anterior`() {
        val history = listOf(
            ChatMessage(ChatRole.USER, "Tenho uma ideia de aplicativo offline para controlar estoque"),
            ChatMessage(ChatRole.USER, "vamos usar SQLite e ele precisa funcionar sem internet"),
            ChatMessage(ChatRole.ASSISTANT, "Prompt anterior: aplicativo de estoque offline com SQLite")
        )

        val resolved = engine.resolve(history, "crie um prompt para um cavaleiro dos zodíacos de ouro")

        assertTrue(resolved.objective == "crie um prompt para um cavaleiro dos zodíacos de ouro")
        assertTrue(resolved.context.idea == null)
        assertTrue(resolved.context.requirements.isEmpty())
        assertTrue(resolved.context.decisions.isEmpty())
        assertTrue(resolved.context.artifacts.isEmpty())
    }

    // O envelope removido continha a palavra "prompt", que roteava o follow-up para o gerador de prompts.
    @Test
    fun `follow-up de artefato gerado pelo gerador de prompts continua marcado como prompt`() {
        val prompt = "Ilustração digital detalhada de um foguete decolando, ambientado em um cenário coerente com o assunto. Qualidade: alta definição."
        val history = listOf(
            ChatMessage(ChatRole.USER, "crie um prompt de um foguete decolando"),
            ChatMessage(ChatRole.ASSISTANT, "Encontrei um prompt de referência na biblioteca e adaptei ao seu pedido (estimativa heurística interna — qualidade 81%):\n\n$prompt")
        )
        val resolved = engine.resolve(history, "vamos melhorar ele quero ele num deserto ao por do sol com a visão de uma plataforma de longe")

        assertTrue(resolved.objective.contains("tipo da referência: prompt"))
        assertTrue(resolved.objective.contains("artefato anterior: $prompt"))
        assertFalse(resolved.objective.contains("estimativa heurística"))
    }
}
