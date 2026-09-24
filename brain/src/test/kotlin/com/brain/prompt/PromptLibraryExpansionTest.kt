package com.brain.prompt

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptLibraryExpansionTest {
    @Test
    fun `loader incorpora prompts extras de imagem e programacao`() {
        val seed = """
            {"versao":1,"prompts":[{"id":"base","titulo":"Base","categoria":"texto","objetivo":"base","template":"Base","tags":["base"]}]}
        """.trimIndent()

        val prompts = PromptLibraryLoader.fromJson(seed)

        assertTrue(prompts.any { it.id == "imagem_foto_produto_ecommerce" })
        assertTrue(prompts.any { it.id == "imagem_edicao_foto" })
        assertTrue(prompts.any { it.id == "prog_kotlin_android_feature" })
        assertTrue(prompts.any { it.id == "prog_sql_consulta_segura" })
        assertTrue(prompts.any { it.id == "prog_codebase_auditoria_conexao" })
        assertNotNull(prompts.firstOrNull { it.id == "imagem_foto_produto_ecommerce" }?.textoTemplate)
    }
}
