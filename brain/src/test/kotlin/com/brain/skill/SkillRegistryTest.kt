package com.brain.skill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SkillRegistryTest {
    private fun skill(enabled: Boolean = true, trust: TrustLevel = TrustLevel.CORE, source: String = "builtin") = SkillManifest(
        id = "code.analysis", name = "Code Analysis", version = "1.0.0",
        description = "analisa código", category = "development",
        capabilities = setOf("code_analysis"), trustLevel = trust, enabled = enabled,
        sourceId = source
    )

    @Test fun `registra e busca por capability`() {
        val registry = SkillRegistry()
        registry.register(skill())
        assertEquals(listOf("code.analysis"), registry.findForCapability("code_analysis").map { it.manifest.id })
    }

    @Test fun `skill brain-builtin é reconhecida como built-in`() {
        val registry = SkillRegistry()
        registry.register(skill(source = "brain-builtin"))
        assertTrue(registry.isUsable("code.analysis"))
    }

    @Test(expected = SecurityException::class)
    fun `skill externa não verificada não pode ser ativada`() {
        SkillRegistry().register(skill(trust = TrustLevel.UNTRUSTED))
    }

    @Test(expected = SecurityException::class)
    fun `skill externa ativa sem assinatura é rejeitada`() {
        SkillRegistry().register(skill(source = "remote-catalog"))
    }

    @Test fun `revogação impede uso e novo registro`() {
        val registry = SkillRegistry()
        registry.register(skill())
        registry.revoke("code.analysis", "conteúdo inseguro")
        assertFalse(registry.isUsable("code.analysis"))
        assertTrue(registry.get("code.analysis")!!.revoked)
    }

    @Test
    fun `bridge registra Roofts descoberto desabilitado com hash e origem`() {
        val skill = RooftsSkill(
            id = "security-review",
            description = "Revisão de segurança",
            body = "conteúdo metodológico",
            contentHash = "a".repeat(64),
            sourcePath = "roofts/0.6/skills/security-review/SKILL.md",
            origin = "roofts-0.6",
            triggers = setOf("segurança")
        )
        val registry = SkillRegistry()
        val record = RooftsSkillManifestBridge.registerDiscovered(skill, registry)

        assertFalse(record.manifest.enabled)
        assertEquals(skill.contentHash, record.manifest.contentHash)
        assertEquals(skill.origin, record.manifest.sourceId)
        assertTrue(registry.listEnabled().isEmpty())
        assertTrue(registry.get("roofts.security-review") != null)
    }

    @Test(expected = SecurityException::class)
    fun `bridge não permite habilitar Roofts sem assinatura`() {
        val skill = RooftsSkill(
            id = "unsigned-skill",
            description = "Skill sem assinatura",
            body = "conteúdo",
            contentHash = "b".repeat(64),
            origin = "roofts-0.6"
        )
        val registry = SkillRegistry()
        RooftsSkillManifestBridge.registerDiscovered(skill, registry)
        registry.register(RooftsSkillManifestBridge.manifest(skill, enabled = true))
    }

    @Test(expected = SecurityException::class)
    fun `revogação persiste após reinicialização do registry`() {
        val file = File.createTempFile("skill-revocations-", ".tsv")
        try {
            val first = SkillRegistry(revocationFile = file)
            first.register(skill())
            first.revoke("code.analysis", "conteúdo inseguro")
            SkillRegistry(revocationFile = file).register(skill())
        } finally { file.delete() }
    }
}
