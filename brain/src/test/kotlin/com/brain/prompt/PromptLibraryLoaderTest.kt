package com.brain.prompt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.StringReader

class PromptLibraryLoaderTest {

    @Test
    fun `le INSERT com aspas escapadas, ponto e virgula e hifens dentro do texto`() {
        val sql = """
            -- comentario de cabecalho; com ponto e virgula e 'aspas soltas
            DROP TABLE IF EXISTS coding_categories;
            CREATE TABLE coding_categories (id INTEGER PRIMARY KEY, name VARCHAR(100)); -- fim
            INSERT INTO coding_categories (id, name) VALUES
            (1, 'Code Review');
            INSERT INTO coding_prompts (id, category_id, title, use_when, prompt_text) VALUES
            (7, 1, 'Revisar PR', 'Antes do merge', 'Revise [MODULO]; it''s -- not a comment; ok'),
            (8, 1, NULL, NULL, 'Segundo prompt');
        """.trimIndent()

        val templates = PromptLibraryLoader.fromSql(StringReader(sql))

        assertEquals(2, templates.size)
        val primeiro = templates.first { it.id == "sql:coding_prompts:7" }
        assertEquals("Revise [MODULO]; it's -- not a comment; ok", primeiro.textoTemplate)
        assertEquals("Revisar PR", primeiro.finalidade)
        assertTrue(primeiro.contextoDeUso.contains("Code Review"))
        assertTrue(primeiro.contextoDeUso.contains("Antes do merge"))
        assertEquals("code.generation", primeiro.skillRelacionada)
        assertEquals(1, primeiro.versao)

        val semTitulo = templates.first { it.id == "sql:coding_prompts:8" }
        assertEquals("Segundo prompt", semTitulo.finalidade)
        assertEquals("Segundo prompt", semTitulo.textoTemplate)
    }

    @Test
    fun `ignora linhas sem texto e tabelas que nao sao de prompt`() {
        val sql = """
            INSERT INTO situations (id, name, tagline, description) VALUES (1, 'Team Communication', NULL, NULL);
            INSERT INTO related_prompts (prompt_number, situation_id, title) VALUES (51, 1, 'OKR Design');
            INSERT INTO users (name, email) VALUES ('x', 'y');
            INSERT INTO money_prompts (id, section_id, title, prompt_text, context_note, payoff) VALUES
            (10, 1, 'Sem texto', NULL, NULL, NULL),
            (11, 1, 'Com texto', 'Escreva sobre [TEMA]', NULL, NULL);
        """.trimIndent()

        val templates = PromptLibraryLoader.fromSql(StringReader(sql))

        assertEquals(listOf("sql:money_prompts:11"), templates.map { it.id })
    }

    @Test
    fun `categoria vem da tabela auxiliar mesmo quando o INSERT dela vem depois, e tags sao dobradas no contexto`() {
        val sql = """
            INSERT INTO gallery_prompts (id, title, category, prompt_text, notes, lang_hint, popularity) VALUES
            ('tech-sql-001', 'Optimize SQL Query', 'Technical / Data', 'Otimize a query {{query}}', NULL, NULL, 57);
            INSERT INTO gallery_tags (prompt_id, tag) VALUES ('tech-sql-001', 'postgres'), ('tech-sql-001', 'indices');
            INSERT INTO prompts (id, situation_id, title, prompt_text, pro_tip) VALUES
            (21, 1, 'All-Hands Talking Points', 'Prepare pontos para [EVENTO]', NULL);
            INSERT INTO situations (id, name, tagline, description) VALUES (1, 'Team Communication', NULL, NULL);
        """.trimIndent()

        val templates = PromptLibraryLoader.fromSql(StringReader(sql)).associateBy { it.id }

        val galeria = templates.getValue("sql:gallery_prompts:tech-sql-001")
        assertTrue(galeria.contextoDeUso.contains("Technical / Data"))
        assertTrue(galeria.contextoDeUso.contains("postgres"))
        assertTrue(galeria.contextoDeUso.contains("indices"))
        assertEquals("Otimize a query {{query}}", galeria.textoTemplate)

        val situacao = templates.getValue("sql:prompts:21")
        assertTrue(situacao.contextoDeUso.contains("Team Communication"))
    }

    @Test
    fun `llmprompts junta system e user prompt e aceita so user prompt`() {
        val sql = """
            INSERT INTO llmprompts_prompts (id, slug, title, description, system_prompt, system_ref, user_prompt, example_model, example_temperature, example_output) VALUES
            (1, 'a', 'Com system', 'desc', 'Voce e um revisor.', NULL, 'Revise isto.', NULL, NULL, NULL),
            (2, 'b', 'Com ref', 'desc', NULL, 'a', 'Continue.', NULL, NULL, NULL);
        """.trimIndent()

        val templates = PromptLibraryLoader.fromSql(StringReader(sql)).associateBy { it.id }

        assertEquals("Voce e um revisor.\n\nRevise isto.", templates.getValue("sql:llmprompts_prompts:1").textoTemplate)
        assertEquals("Continue.", templates.getValue("sql:llmprompts_prompts:2").textoTemplate)
        assertNull(templates.getValue("sql:llmprompts_prompts:2").agenteRelacionado)
    }

    @Test
    fun `contexto e finalidade ficam curtos mesmo com texto enorme`() {
        val gigante = "palavra ".repeat(50_000)
        val sql = "INSERT INTO extra_prompts (id, area, title, prompt_text, source, source_url) VALUES (1, 'growth', NULL, '$gigante', NULL, NULL);"

        val template = PromptLibraryLoader.fromSql(StringReader(sql)).single()

        assertTrue(template.finalidade.length <= 120)
        assertTrue(template.contextoDeUso.length <= 500)
        assertEquals(gigante, template.textoTemplate)
    }

    @Test
    fun `duas passagens preservam quantidade ids categorias tags prompts grandes e ordem`() {
        val gigante = "conteudo ".repeat(20_000)
        val sql = """
            INSERT INTO gallery_prompts (id, title, category, prompt_text) VALUES
            ('p-large', 'Large', 'Technical / Data', '$gigante');
            INSERT INTO gallery_tags (prompt_id, tag) VALUES ('p-large', 'postgres'), ('p-large', 'indices');
            INSERT INTO prompts (id, situation_id, title, prompt_text) VALUES
            (2, 7, 'Segundo', 'Texto segundo'),
            (1, 7, 'Primeiro', 'Texto primeiro');
            INSERT INTO situations (id, name) VALUES (7, 'Team Communication');
        """.trimIndent()

        var passagens = 0
        val templates = PromptLibraryLoader.fromSql {
            passagens++
            StringReader(sql)
        }.associateBy { it.id }

        assertEquals(2, passagens)
        assertEquals(3, templates.size)
        assertEquals(setOf("sql:gallery_prompts:p-large", "sql:prompts:1", "sql:prompts:2"), templates.keys)
        assertTrue(templates.getValue("sql:gallery_prompts:p-large").contextoDeUso.contains("postgres"))
        assertTrue(templates.getValue("sql:gallery_prompts:p-large").contextoDeUso.contains("indices"))
        assertEquals(gigante, templates.getValue("sql:gallery_prompts:p-large").textoTemplate)
        assertTrue(templates.getValue("sql:prompts:1").contextoDeUso.contains("Team Communication"))
        assertEquals("Texto segundo", templates.getValue("sql:prompts:2").textoTemplate)
    }

    @Test
    fun `biblioteca SQL real do app carrega todos os prompts com ids unicos`() {
        // O diretório de trabalho dos testes do módulo é brain/.
        val arquivo = File("../app/src/main/assets/${PromptLibraryLoader.ASSET_NAME}")
        assertTrue("SQL não encontrado em ${arquivo.absolutePath}", arquivo.isFile)

        val templates = PromptLibraryLoader.fromSql { arquivo.bufferedReader() }

        // 13.864 registros nas 13 tabelas de prompt; 1 (money_prompts id 10) não tem texto e é ignorado.
        assertEquals(13_863, templates.size)
        assertEquals(templates.size, templates.map { it.id }.toSet().size)
        assertTrue(templates.all { it.finalidade.isNotBlank() && it.textoTemplate.isNotBlank() })
        assertTrue(templates.any { it.id.startsWith("sql:crafti_prompts:") })
        assertTrue(templates.any { it.id.startsWith("sql:promptforge_prompts:") })
    }
}
