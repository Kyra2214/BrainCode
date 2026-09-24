package com.brain.skill

import com.brain.memory.SkillCandidate

/** Resultado de um teste isolado de Skill; saída não é automaticamente verdade. */
data class SkillValidationEvidence(
    val skillId: String,
    val sandboxPassed: Boolean,
    val testsPassed: Boolean,
    val criticPassed: Boolean,
    val evidence: List<String>
) {
    init {
        require(skillId.isNotBlank()) { "skillId é obrigatório" }
        require(evidence.none { it.isBlank() }) { "evidência não pode ser vazia" }
    }

    val passed: Boolean get() = sandboxPassed && testsPassed && criticPassed
}

class SkillValidator(
    private val sandbox: (SkillManifest, String?) -> SkillValidationEvidence,
    private val critic: (SkillValidationEvidence) -> Boolean
) {
    /** Valida sem registrar; o chamador decide se deve promover o candidato. */
    fun validate(candidate: SkillCandidate, content: String? = null): SkillValidationEvidence {
        val sandboxEvidence = sandbox(candidate.manifest, content)
        val sandboxAndTestsPassed = sandboxEvidence.sandboxPassed && sandboxEvidence.testsPassed
        val criticPassed = sandboxAndTestsPassed && critic(sandboxEvidence.copy(criticPassed = true))
        return sandboxEvidence.copy(criticPassed = criticPassed)
    }

    /** Único caminho de promoção: validação PASS seguida de registro especializado. */
    fun promote(
        candidate: SkillCandidate,
        validation: SkillValidationEvidence,
        registry: SkillRegistry,
        content: String? = null
    ): SkillRecord {
        require(validation.skillId == candidate.manifest.id) { "evidência pertence a outra Skill" }
        require(validation.passed) { "Skill não passou por Sandbox/Test/Critic" }
        return registry.register(candidate.manifest, content)
    }
}
