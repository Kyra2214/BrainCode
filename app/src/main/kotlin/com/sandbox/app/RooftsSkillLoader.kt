package com.sandbox.app

import android.content.Context
import com.brain.skill.RooftsSkill
import com.brain.skill.RooftsSkillActivationPlan
import com.brain.skill.RooftsSkillActivationPlanner
import com.brain.skill.RooftsSkillSelector
import com.brain.skill.SafeSkillExecutionRequest
import com.brain.skill.SafeSkillExecutionResult
import com.brain.skill.SafeSkillResourceExecutor
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** Resultado de descoberta: só metadados ficam retidos até uma Skill ser selecionada. */
data class RooftsSkillSelection(
    val skills: List<RooftsSkill>,
    val activationPlan: RooftsSkillActivationPlan
)

class RooftsSkillCatalog internal constructor(
    private val context: Context,
    private val metadata: List<RooftsSkill>
) {
    fun metadata(): List<RooftsSkill> = metadata

    /** Seleciona localmente e só então relê o corpo das Skills escolhidas. */
    fun select(objective: String, max: Int = RooftsSkillSelector.MAX_SKILLS): List<RooftsSkill> =
        activate("skill-selection", objective, max).skills

    fun activate(runId: String, objective: String, max: Int = RooftsSkillSelector.MAX_SKILLS): RooftsSkillSelection {
        val plan = RooftsSkillActivationPlanner.plan(runId, objective, metadata, max)
        val skills = plan.approvedSkillIds.mapNotNull { id -> metadata.firstOrNull { it.id == id } }
            .map { skill -> RooftsSkillLoader.loadBody(context, skill) ?: skill }
        return RooftsSkillSelection(skills, plan)
    }

    /** Recursos só podem ser lidos como arquivos relativos ao diretório da própria Skill. */
    fun loadResource(skill: RooftsSkill, resourcePath: String): String {
        val normalized = resourcePath.trim()
        require(normalized.isNotBlank() && !normalized.startsWith('/') && !normalized.startsWith('\\')) {
            "recurso de Skill inválido"
        }
        require(normalized.split('/', '\\').none { it.isBlank() || it == ".." }) {
            "recurso de Skill inseguro"
        }
        require(normalized in skill.resources) { "recurso não declarado pela Skill: $normalized" }
        val source = requireNotNull(skill.sourcePath) { "Skill sem caminho de origem" }
        val base = source.substringBeforeLast('/', missingDelimiterValue = "")
        val target = "$base/$normalized"
        return context.assets.open(target).bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    /** Hook explícito e opt-in: sem permissões, sem rede e apenas para ferramenta declarada. */
    fun executeDeclaredHook(
        skill: RooftsSkill,
        executable: String,
        arguments: List<String>,
        workspace: java.io.File,
        executor: SafeSkillResourceExecutor
    ): SafeSkillExecutionResult {
        require(skill.tools.contains(executable)) { "hook não declarado pela Skill" }
        require(skill.requiredPermissions.isEmpty()) { "hook exige aprovação de permissão" }
        require(skill.networkPolicy == "none") { "hook com rede exige política dedicada" }
        return executor.execute(SafeSkillExecutionRequest(executable, arguments, workspace, networkAllowed = false))
    }
}

/**
 * Lê `roofts/0.6/skills/<nome>/SKILL.md` dos assets do app. O catálogo leve orienta a descoberta;
 * nenhum texto, recurso ou script de Skill concede capability ou autorização.
 */
object RooftsSkillLoader {
    private const val BASE_PATH = "roofts/0.6/skills"

    /** Compatibilidade legada: materializa tudo; novos callers devem preferir [loadCatalog]. */
    fun load(context: Context): List<RooftsSkill> = loadCatalog(context).metadata().map { skill ->
        loadBody(context, skill) ?: skill
    }

    /** Descoberta leve: retém frontmatter, hash e origem, mas não retém o corpo Markdown. */
    fun loadCatalog(context: Context): RooftsSkillCatalog {
        val appContext = context.applicationContext ?: context
        val overlay = loadOverlay(appContext)
        val pastas = appContext.assets.list(BASE_PATH)?.toList().orEmpty()
        val metadata = pastas.mapNotNull { pasta ->
            runCatching { lerSkill(appContext, pasta, includeBody = false) }.getOrNull()
        }.map { skill -> mergeOverlay(skill, overlay[skill.id]) }
            .sortedBy { it.id }
        return RooftsSkillCatalog(appContext, metadata)
    }

    internal fun loadBody(context: Context, skill: RooftsSkill): RooftsSkill? {
        val sourcePath = skill.sourcePath ?: return null
        return runCatching {
            val conteudo = context.assets.open(sourcePath).bufferedReader(Charsets.UTF_8).use { it.readText() }
            parse(conteudo, idFallback = skill.id, sourcePath = sourcePath, includeBody = true)
                ?.let { parsed -> mergeOverlay(parsed, OverlayMetadata.fromSkill(skill)) }
        }.getOrNull()
    }

    private data class OverlayMetadata(
        val triggers: Set<String> = emptySet(),
        val exclusions: Set<String> = emptySet(),
        val resources: Set<String> = emptySet(),
        val requiredPermissions: Set<String> = emptySet(),
        val requiredCapabilities: Set<String> = emptySet(),
        val tools: Set<String> = emptySet(),
        val networkPolicy: String? = null,
        val overlayHash: String
    ) {
        companion object {
            fun fromSkill(skill: RooftsSkill): OverlayMetadata = OverlayMetadata(
                triggers = skill.triggers,
                exclusions = skill.exclusions,
                resources = skill.resources,
                requiredPermissions = skill.requiredPermissions,
                requiredCapabilities = skill.requiredCapabilities,
                tools = skill.tools,
                networkPolicy = skill.networkPolicy.takeUnless { it == "none" },
                overlayHash = skill.overlayHash.orEmpty()
            )
        }
    }

    private fun loadOverlay(context: Context): Map<String, OverlayMetadata> = runCatching {
        val content = context.assets.open(OVERLAY_PATH).bufferedReader(Charsets.UTF_8).use { it.readText() }
        val hash = sha256(content)
        val items = JSONObject(content).optJSONArray("skills") ?: JSONArray()
        (0 until items.length()).mapNotNull { index ->
            val item = items.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("id").trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
            id to OverlayMetadata(
                triggers = jsonSet(item, "triggers"),
                exclusions = jsonSet(item, "exclusions"),
                resources = jsonSet(item, "resources"),
                requiredPermissions = jsonSet(item, "requiredPermissions"),
                requiredCapabilities = jsonSet(item, "requiredCapabilities"),
                tools = jsonSet(item, "tools"),
                networkPolicy = item.optString("networkPolicy").takeIf { it.isNotBlank() },
                overlayHash = hash
            )
        }.toMap()
    }.getOrDefault(emptyMap())

    private fun jsonSet(item: JSONObject, key: String): Set<String> =
        (item.optJSONArray(key) ?: JSONArray()).let { array ->
            (0 until array.length()).mapNotNull { array.optString(it).trim().takeIf(String::isNotBlank) }.toSet()
        }

    /** Frontmatter do upstream vence o overlay; o overlay apenas preenche lacunas locais. */
    private fun mergeOverlay(skill: RooftsSkill, overlay: OverlayMetadata?): RooftsSkill {
        if (overlay == null) return skill
        return skill.copy(
            triggers = skill.triggers.ifEmpty { overlay.triggers },
            exclusions = skill.exclusions.ifEmpty { overlay.exclusions },
            resources = skill.resources.ifEmpty { overlay.resources },
            overlayHash = overlay.overlayHash,
            requiredPermissions = skill.requiredPermissions.ifEmpty { overlay.requiredPermissions },
            requiredCapabilities = skill.requiredCapabilities.ifEmpty { overlay.requiredCapabilities },
            tools = skill.tools.ifEmpty { overlay.tools },
            networkPolicy = if (skill.networkPolicy != "none") skill.networkPolicy else overlay.networkPolicy ?: skill.networkPolicy
        )
    }

    private fun lerSkill(context: Context, pasta: String, includeBody: Boolean): RooftsSkill? {
        val caminho = "$BASE_PATH/$pasta/SKILL.md"
        val conteudo = context.assets.open(caminho).bufferedReader(Charsets.UTF_8).use { it.readText() }
        return parse(conteudo, idFallback = pasta, sourcePath = caminho, includeBody = includeBody)
    }

    /** Exposto para teste sem depender de Context/assets. */
    internal fun parse(conteudo: String, idFallback: String): RooftsSkill? =
        parse(conteudo, idFallback, sourcePath = null, includeBody = true)

    internal fun parse(conteudo: String, idFallback: String, sourcePath: String?): RooftsSkill? =
        parse(conteudo, idFallback, sourcePath, includeBody = true)

    /**
     * Parser deliberadamente pequeno: entende o frontmatter documentado pelo upstream sem
     * transformar Markdown em autoridade. Listas aceitam tanto `[a, b]` quanto itens `- a`.
     */
    internal fun parse(
        conteudo: String,
        idFallback: String,
        sourcePath: String?,
        includeBody: Boolean
    ): RooftsSkill? {
        val partes = conteudo.split(FRONTMATTER_DELIMITER, limit = 3)
        if (partes.size < 3) return null
        val frontmatter = partes[1]
        val corpo = partes[2].trim()
        val nome = campoFrontmatter(frontmatter, "name") ?: idFallback
        val description = campoFrontmatter(frontmatter, "description") ?: return null
        if (nome.isBlank() || description.isBlank() || corpo.isBlank()) return null
        return RooftsSkill(
            id = nome,
            description = description,
            body = if (includeBody) corpo else "",
            triggers = campoLista(frontmatter, "triggers", "trigger_examples"),
            exclusions = campoLista(frontmatter, "exclusions", "avoid_when", "avoid-when"),
            resources = campoLista(frontmatter, "resources", "references"),
            contentHash = sha256(conteudo),
            sourcePath = sourcePath,
            requiredPermissions = campoLista(frontmatter, "required_permissions", "required-permissions", "permissions"),
            requiredCapabilities = campoLista(frontmatter, "required_capabilities", "required-capabilities", "capabilities"),
            tools = campoLista(frontmatter, "tools"),
            networkPolicy = campoFrontmatter(frontmatter, "network_policy")
                ?: campoFrontmatter(frontmatter, "network-policy")
                ?: "none"
        )
    }

    private fun campoFrontmatter(frontmatter: String, chave: String): String? =
        frontmatter.lines()
            .firstOrNull { it.trimStart().startsWith("$chave:") }
            ?.substringAfter("$chave:")
            ?.trim()
            ?.trim('"', '\'')
            ?.ifBlank { null }

    private fun campoLista(frontmatter: String, vararg chaves: String): Set<String> {
        val linhas = frontmatter.lines()
        chaves.forEach { chave ->
            val indice = linhas.indexOfFirst { it.trimStart().startsWith("$chave:") }
            if (indice < 0) return@forEach
            val linha = linhas[indice].substringAfter(":").trim()
            if (linha.isNotBlank()) return parseLista(linha)
            val itens = buildList {
                for (seguinte in linhas.drop(indice + 1)) {
                    val item = seguinte.trim()
                    if (!item.startsWith("-")) break
                    add(item.removePrefix("-").trim().trim('"', '\''))
                }
            }.filter(String::isNotBlank).toSet()
            if (itens.isNotEmpty()) return itens
        }
        return emptySet()
    }

    private fun parseLista(valor: String): Set<String> = valor
        .removePrefix("[")
        .removeSuffix("]")
        .split(',')
        .map { it.trim().trim('"', '\'') }
        .filter(String::isNotBlank)
        .toSet()

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private const val FRONTMATTER_DELIMITER = "---"
    private const val OVERLAY_PATH = "roofts/0.6/braincode-skill-overlay.json"
}
