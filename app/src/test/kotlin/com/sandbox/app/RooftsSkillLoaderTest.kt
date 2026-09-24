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

    @Test
    fun `carrega triggers exclusoes recursos hash e caminho sem autorizar nada`() {
        val conteudo = """
            ---
            name: secure-review
            description: Review code before release.
            triggers: [review, release]
            avoid_when:
              - marketing copy
            resources: [references/security.md]
            ---

            # Secure Review
            Use evidence and preserve the policy boundary.
        """.trimIndent()

        val skill = RooftsSkillLoader.parse(conteudo, idFallback = "fallback", sourcePath = "assets/secure/SKILL.md")

        assertEquals(setOf("review", "release"), skill?.triggers)
        assertEquals(setOf("marketing copy"), skill?.exclusions)
        assertEquals(setOf("references/security.md"), skill?.resources)
        assertEquals("assets/secure/SKILL.md", skill?.sourcePath)
        assertEquals(64, skill?.contentHash?.length)
        assertEquals("MIT", skill?.license)
    }

    @Test
    fun `metadata-only preserva manifesto mas deixa corpo vazio`() {
        val conteudo = """
            ---
            name: lazy-skill
            description: Select only when needed.
            resources: [references/guide.md]
            ---

            # Large body
            This body must be loaded only after selection.
        """.trimIndent()

        val metadata = RooftsSkillLoader.parse(
            conteudo,
            idFallback = "fallback",
            sourcePath = "roofts/0.6/skills/lazy-skill/SKILL.md",
            includeBody = false
        )

        assertEquals("lazy-skill", metadata?.id)
        assertEquals("", metadata?.body)
        assertEquals(setOf("references/guide.md"), metadata?.resources)
    }
}
