package com.sandbox.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RooftsSkillLoaderTest {
    @Test
    fun `parseia frontmatter e corpo de um SKILL_md real`() {
        val conteudo = """
            ---
            name: planning-and-task-breakdown
            description: Breaks work into ordered tasks. Use when you have a spec.
            ---

            # Planning and Task Breakdown

            ## Overview

            Decompose work into small, verifiable tasks.
        """.trimIndent()
        val skill = RooftsSkillLoader.parse(conteudo, idFallback = "pasta-qualquer")
        assertEquals("planning-and-task-breakdown", skill?.id)
        assertEquals("Breaks work into ordered tasks. Use when you have a spec.", skill?.description)
        assertEquals(true, skill?.body?.startsWith("# Planning and Task Breakdown"))
    }

    @Test
    fun `sem frontmatter nao gera skill`() {
        assertNull(RooftsSkillLoader.parse("# Só corpo, sem frontmatter", idFallback = "x"))
    }

    @Test
    fun `sem description nao gera skill`() {
        val conteudo = "---\nname: sem-description\n---\n\ncorpo"
        assertNull(RooftsSkillLoader.parse(conteudo, idFallback = "x"))
    }

    @Test
    fun `usa idFallback quando o frontmatter nao tem name`() {
        val conteudo = "---\ndescription: algo\n---\n\ncorpo"
        val skill = RooftsSkillLoader.parse(conteudo, idFallback = "pasta-x")
        assertEquals("pasta-x", skill?.id)
    }
}
