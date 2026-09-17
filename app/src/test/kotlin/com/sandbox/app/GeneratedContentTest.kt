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
}
