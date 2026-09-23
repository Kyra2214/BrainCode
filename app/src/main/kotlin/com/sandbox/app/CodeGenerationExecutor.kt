package com.sandbox.app

import com.brain.capability.CapabilityDefinition
import com.brain.gateway.ActionExecution
import com.brain.gateway.ActionExecutor
import com.brain.gateway.ActionRequest
import com.brain.policy.PolicyDecision
import com.brain.router.PapelPipeline
import com.brain.skill.RooftsSkill
import com.brain.skill.RooftsSkillSelector
import com.sandbox.sandbox.Project
import com.sandbox.sandbox.WorkspaceManager
import java.io.File

/** Gera arquivos a partir do objetivo e grava somente dentro do projeto ativo. */
class CodeGenerationExecutor(
    private val gateway: BrainApiGateway,
    private val workspace: WorkspaceManager,
    private val activeProjectName: () -> String,
    /** Catálogo de skills de metodologia do RootFS 0.6 (item 4/5 do plano); vazio = sem contexto extra. */
    private val rooftsSkills: List<RooftsSkill> = emptyList()
) : ActionExecutor {
    override fun execute(
        request: ActionRequest,
        capability: CapabilityDefinition,
        decision: PolicyDecision
    ): ActionExecution {
        val objective = request.parameters["parameter.0"]?.trim().orEmpty()
        if (objective.isBlank()) {
            return ActionExecution(false, error = "objetivo de geração ausente", provenance = provenance(capability))
        }
        return try {
            // Seleção 100% local (sem custo de API) de quais skills de metodologia do RootFS 0.6
            // são relevantes para este objetivo — a Porta 3 continua usando API desde o início
            // (item 3.1 do PLANO_ESCALONAMENTO), a IA só recebe mais contexto sobre como abordar a tarefa.
            val skillsSelecionadas = RooftsSkillSelector.select(objective, rooftsSkills)
            val prompt = """
                Gere um projeto executável para o objetivo abaixo.
                Responda somente com blocos de arquivos no formato exato:
                ```caminho/relativo
                conteúdo completo do arquivo
                ```
                Não use caminhos absolutos, '..', symlinks, binários ou explicações fora dos blocos.
                Inclua os arquivos mínimos necessários para o build suportado.
                ${contextoSkills(skillsSelecionadas)}
                Objetivo:
                $objective
            """.trimIndent()
            val response = gateway.complete(prompt, PapelPipeline.PRODUCAO_DE_ARTEFATO, decision.authorizedAccountIds)
            val files = parseFiles(response.text)
            if (files.isEmpty()) {
                return ActionExecution(false, error = "A IA não retornou blocos de arquivos válidos", provenance = provenance(capability))
            }
            val project = openOrCreateProject(activeProjectName())
            val written = files.map { (relativePath, content) -> writeSafe(project, relativePath, content) }
            ActionExecution(
                success = true,
                result = "${written.size} arquivo(s) gerado(s) em ${project.name}",
                evidence = written.map { "file:$it" } +
                    "provider:${response.providerId}" +
                    "model:${response.modelId}" +
                    skillsSelecionadas.map { "skill-rootfs06:${it.id}" },
                provenance = provenance(capability)
            )
        } catch (error: IllegalStateException) {
            ActionExecution(
                false,
                error = "Não foi possível gerar arquivos. Configure uma chave em Configurações → Provedores. ${error.message.orEmpty()}".trim(),
                provenance = provenance(capability)
            )
        } catch (error: Exception) {
            ActionExecution(false, error = "Falha ao gerar arquivos: ${error.message ?: error.javaClass.simpleName}", provenance = provenance(capability))
        }
    }

    private fun openOrCreateProject(name: String): Project =
        runCatching { workspace.openProject(name) }.getOrElse { workspace.createProject(name) }

    private fun writeSafe(project: Project, relativePath: String, content: String): String {
        val path = relativePath.trim()
        require(path.isNotBlank() && !path.startsWith('/') && !path.startsWith('\\')) { "caminho de arquivo inválido" }
        require(!path.split('/', '\\').any { it == ".." || it.isBlank() }) { "caminho de arquivo inseguro" }
        require(path.length <= 240 && content.length <= MAX_FILE_BYTES) { "arquivo excede o limite permitido" }
        val target = File(project.path, path)
        val canonicalProject = project.path.canonicalFile
        val canonicalTarget = target.canonicalFile
        require(canonicalTarget.path.startsWith(canonicalProject.path + File.separator)) { "caminho fora do projeto" }
        target.parentFile?.mkdirs()
        target.writeText(content)
        return canonicalTarget.relativeTo(canonicalProject).path
    }

    /**
     * Injeta as skills selecionadas como contexto/instrução, mesmo padrão que o
     * `GatewayPromptImprover` já usa para "pontos fracos" e "contexto de pesquisa" (Porta 2):
     * a IA recebe orientação de como abordar a tarefa, mas quem decide se o resultado é aceito
     * continua sendo o parser de arquivos e as validações de caminho abaixo — sem mudança de autoridade.
     */
    private fun contextoSkills(skills: List<RooftsSkill>): String {
        if (skills.isEmpty()) return ""
        val trechos = skills.joinToString("\n\n") { skill ->
            "### ${skill.id}\n${skill.body.take(MAX_SKILL_BODY_CHARS)}"
        }
        return """

            Guias de metodologia a seguir ao abordar esta tarefa (aplique apenas o que fizer sentido para o objetivo):
            $trechos

        """.trimIndent() + "\n"
    }

    private fun provenance(capability: CapabilityDefinition) = listOf("app:CodeGenerationExecutor", "capability:${capability.id}")

    companion object {
        private const val MAX_FILE_BYTES = 2 * 1024 * 1024
        private const val MAX_SKILL_BODY_CHARS = 1500
        private val FILE_BLOCK = Regex("""(?s)```\s*([^\n`]+)\n(.*?)```""")

        fun parseFiles(text: String): List<Pair<String, String>> = FILE_BLOCK.findAll(text)
            .map { match -> match.groupValues[1].trim() to match.groupValues[2].trimEnd() }
            .filter { it.first.isNotBlank() && it.second.isNotBlank() }
            .toList()
    }
}
