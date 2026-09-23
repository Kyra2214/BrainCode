package com.brain.skill

/**
 * Skill de metodologia lida de `opt/roofts/0.6/skills/<nome>/SKILL.md` (frontmatter + corpo em
 * Markdown). Isto é um guia de "como abordar tarefas de desenvolvimento" — não concede nenhuma
 * capability nem autorização; é apenas contexto/instrução para a Porta 3. Ver [SkillRegistry] para
 * o catálogo que de fato concede capabilities.
 *
 * @param id nome da pasta (`frontmatter.name`), usado como identificador estável.
 * @param description linha de gatilho/descrição do frontmatter (geralmente em inglês).
 * @param body corpo em Markdown após o frontmatter, sem o `---` de abertura/fechamento.
 */
data class RooftsSkill(
    val id: String,
    val description: String,
    val body: String
)
