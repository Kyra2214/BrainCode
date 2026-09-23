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
 * @param triggers sinais explícitos de ativação; são descoberta, não autorização.
 * @param exclusions sinais de não ativação; evitam carregar uma Skill por coincidência.
 * @param resources caminhos relativos que podem ser carregados sob demanda.
 * @param contentHash SHA-256 do arquivo completo, para provenance e invalidação de cache.
 * @param sourcePath caminho do asset/recurso que originou o conteúdo.
 */
data class RooftsSkill(
    val id: String,
    val description: String,
    val body: String,
    val triggers: Set<String> = emptySet(),
    val exclusions: Set<String> = emptySet(),
    val resources: Set<String> = emptySet(),
    val contentHash: String? = null,
    val sourcePath: String? = null,
    val origin: String = "roofts-0.6",
    val license: String? = "MIT",
    val requiredPermissions: Set<String> = emptySet(),
    val requiredCapabilities: Set<String> = emptySet(),
    val tools: Set<String> = emptySet(),
    val networkPolicy: String = "none"
)
