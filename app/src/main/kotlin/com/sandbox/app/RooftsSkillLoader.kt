package com.sandbox.app

import android.content.Context
import com.brain.skill.RooftsSkill

/**
 * Lê `roofts/0.6/skills/<nome>/SKILL.md` dos assets do app e transforma cada arquivo em um
 * [RooftsSkill] (nome/description do frontmatter + corpo em Markdown). Isto só lê e interpreta
 * conteúdo já embutido no app — nenhuma rede, nenhuma autorização é concedida aqui (ver
 * `RooftsSkillSelector` para a seleção local e `PolicyBroker` para autorização).
 */
object RooftsSkillLoader {
    private const val BASE_PATH = "roofts/0.6/skills"

    fun load(context: Context): List<RooftsSkill> {
        val pastas = context.assets.list(BASE_PATH)?.toList().orEmpty()
        return pastas.mapNotNull { pasta ->
            runCatching { lerSkill(context, pasta) }.getOrNull()
        }.sortedBy { it.id }
    }

    private fun lerSkill(context: Context, pasta: String): RooftsSkill? {
        val caminho = "$BASE_PATH/$pasta/SKILL.md"
        val conteudo = context.assets.open(caminho).bufferedReader(Charsets.UTF_8).use { it.readText() }
        return parse(conteudo, idFallback = pasta)
    }

    /** Exposto para teste sem depender de Context/assets. */
    internal fun parse(conteudo: String, idFallback: String): RooftsSkill? {
        val partes = conteudo.split(FRONTMATTER_DELIMITER, limit = 3)
        // conteudo esperado: "" , frontmatter, corpo — split por "---" em até 3 pedaços.
        if (partes.size < 3) return null
        val frontmatter = partes[1]
        val corpo = partes[2].trim()
        val nome = campoFrontmatter(frontmatter, "name") ?: idFallback
        val description = campoFrontmatter(frontmatter, "description") ?: return null
        if (nome.isBlank() || description.isBlank() || corpo.isBlank()) return null
        return RooftsSkill(id = nome, description = description, body = corpo)
    }

    /** Todas as skills do RootFS 0.6 hoje trazem `name`/`description` numa única linha. */
    private fun campoFrontmatter(frontmatter: String, chave: String): String? =
        frontmatter.lines()
            .firstOrNull { it.trimStart().startsWith("$chave:") }
            ?.substringAfter("$chave:")
            ?.trim()
            ?.ifBlank { null }

    private const val FRONTMATTER_DELIMITER = "---"
}
