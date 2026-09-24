package com.brain.research

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuerySanitizerTest {
    @Test fun `remove chaves de api e tokens da consulta`() {
        val limpa = QuerySanitizer.sanitizar("pesquise sobre api_key: sk-abcdef1234567890 e me diga mais")
        assertFalse("sk-" in limpa)
        assertFalse("api_key" in limpa.lowercase())
    }

    @Test fun `remove caminhos locais do workspace`() {
        val limpa = QuerySanitizer.sanitizar("erro no arquivo /data/user/0/com.app/workspace/segredo.txt ao compilar")
        assertFalse("/data/" in limpa)
    }

    @Test fun `mantem consulta legitima intacta`() {
        val limpa = QuerySanitizer.sanitizar("técnicas atuais de geração de imagem fotorrealista")
        assertTrue(limpa.contains("fotorrealista"))
    }
}
