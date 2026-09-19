package com.sandbox.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeneratedContentTest {
    @Test
    fun `prompt copia somente conteudo e remove qualidade e fontes`() {
        val prompt = "Encontrei referência (qualidade 84%):\n\nFotografia fotorrealista de um foguete.\n\nContexto pesquisado considerado:\nMusely [https://example.com]"
        assertEquals("Fotografia fotorrealista de um foguete.", copyPayloadFor(prompt, GeneratedContentType.PROMPT))
    }

    @Test
    fun `prompt copiado nao inclui referencia da biblioteca`() {
        val prompt = "Prompt final de um foguete.\n\nReferência da biblioteca considerada (generated-19um8f9): adaptado ao pedido acima."
        assertEquals("Prompt final de um foguete.", copyPayloadFor(prompt, GeneratedContentType.PROMPT))
    }

    @Test
    fun `prompt copia remove blocos tecnicos repetidos`() {
        val bloco = "Estilo: conforme o contexto do pedido\n" +
            "Composição: enquadramento equilibrado, assunto em destaque\n" +
            "Iluminação: iluminação natural e equilibrada\n" +
            "Câmera: câmera fotográfica padrão\n" +
            "Realismo: nível de realismo fotográfico"
        val repetido = "Prompt final de um foguete.\n\n$bloco\n$bloco\n$bloco"

        val resultado = copyPayloadFor(repetido, GeneratedContentType.PROMPT)

        assertEquals(1, Regex("(?im)^Estilo:").findAll(resultado).count())
        assertEquals(1, Regex("(?im)^Composição:").findAll(resultado).count())
        assertEquals(1, Regex("(?im)^Iluminação:").findAll(resultado).count())
        assertEquals(1, Regex("(?im)^Câmera:").findAll(resultado).count())
        assertEquals(1, Regex("(?im)^Realismo:").findAll(resultado).count())
    }

    @Test
    fun `codigo preserva quebras indentacao e caracteres especiais`() {
        val code = "#!/bin/bash\nif [ \"\$1\" = \"x\" ]; then\n  echo \"á\"\nfi\n"
        assertEquals(code, copyPayloadFor(code, GeneratedContentType.SCRIPT))
    }

    @Test
    fun `tipo detecta prompt codigo markdown json yaml e script`() {
        assertEquals(GeneratedContentType.PROMPT, detectGeneratedContentType("resultado", "prompt.library.write"))
        assertEquals(GeneratedContentType.SCRIPT, detectGeneratedContentType("```bash\necho ok\n```"))
        assertEquals(GeneratedContentType.JSON, detectGeneratedContentType("```json\n{}\n```"))
        assertEquals(GeneratedContentType.YAML, detectGeneratedContentType("```yaml\nkey: value\n```"))
        assertTrue(detectGeneratedContentType("texto simples") == GeneratedContentType.TEXT)
    }

    @Test
    fun `esclarecimento do checklist nao e classificado como prompt`() {
        assertEquals(
            GeneratedContentType.CLARIFICATION,
            detectGeneratedContentType(
                "Antes de gerar o prompt, preciso confirmar: qual é a referência?",
                "prompt.library.write",
                listOf("prompt-checklist:aguardando-esclarecimento")
            )
        )
    }
}
