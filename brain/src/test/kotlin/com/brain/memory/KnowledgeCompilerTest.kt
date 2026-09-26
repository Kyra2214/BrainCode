package com.brain.memory

import com.brain.skill.SkillManifest
import com.brain.skill.SkillRegistry
import com.brain.skill.SkillValidationEvidence
import com.brain.skill.SkillValidator
import com.brain.skill.TrustLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KnowledgeCompilerTest {
    private val source = KnowledgeSource("docs", "https://example.com/reference")

    private fun manifest() = SkillManifest(
        id = "procedure.example",
        name = "Example Procedure",
        version = "1.0.0",
        description = "procedimento validado",
        category = "general",
        capabilities = setOf("procedure.run"),
        trustLevel = TrustLevel.CORE,
        sourceId = "builtin"
    )

    @Test
    fun `evidencia com fonte passa por critic e entra como conhecimento validado`() {
        val compiler = KnowledgeCompiler(
            KnowledgeLearningCycle(InMemoryKnowledgeMemory()),
            ConservativeKnowledgeCritic()
        )
        val compilation = compiler.compile(
            KnowledgeEvidence("e-1", "como fazer", "passo a passo", source, provenance = listOf("url:https://example.com/reference"))
        )

        assertEquals(KnowledgeCompilationStatus.VALIDATED, compilation.status)
        assertTrue(compilation.entry.validated)
        assertEquals("passo a passo", compilation.entry.answer)
    }

    @Test
    fun `sem fonte fica candidato e nao entra no recall`() {
        val memory = InMemoryKnowledgeMemory()
        val compiler = KnowledgeCompiler(KnowledgeLearningCycle(memory), ConservativeKnowledgeCritic())
        val compilation = compiler.compile(KnowledgeEvidence("e-2", "pergunta", "resposta", null))

        assertEquals(KnowledgeCompilationStatus.UNCERTAIN, compilation.status)
        assertFalse(compilation.entry.validated)
        assertNull(memory.findValidated("pergunta"))
        assertNull(compiler.proposeSkill(compilation, manifest()))
    }

    @Test
    fun `skill candidate exige validacao explicita antes do registry`() {
        val compiler = KnowledgeCompiler(
            KnowledgeLearningCycle(InMemoryKnowledgeMemory()),
            ConservativeKnowledgeCritic()
        )
        val compilation = compiler.compile(KnowledgeEvidence("e-3", "rotina", "execute assim", source))
        val candidate = compiler.proposeSkill(compilation, manifest())
        val registry = SkillRegistry()

        assertNotNull(candidate)
        assertTrue(registry.get("procedure.example") == null)
        val validator = SkillValidator(
            sandbox = { manifest, _ ->
                SkillValidationEvidence(manifest.id, sandboxPassed = true, testsPassed = true, criticPassed = false, evidence = listOf("sandbox:ok", "tests:ok"))
            },
            critic = { true }
        )
        val validation = validator.validate(candidate!!)
        val record = validator.promote(candidate, validation, registry)

        assertEquals("procedure.example", record.manifest.id)
        assertTrue(registry.isUsable("procedure.example"))
    }
}
