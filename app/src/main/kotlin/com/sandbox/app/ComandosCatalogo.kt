package com.sandbox.app

import android.content.Context
import org.json.JSONObject

/**
 * Um comando do catálogo /comandos (catalog/comandos_catalogo.json). Todo comando aqui é
 * modoExecucao = PROMPT: não executa nada localmente, só monta um prompt especializado e
 * encaminha para o Brain — igual ao chat livre, só que com contexto/objetivo pré-definidos.
 *
 * Comandos operacionais reais (/testlab, /security, /git status, /git diff, /workflow,
 * /approval demo, /workspace new, /sqlite start, /sqlite stop, /discovery, /run, /deliver)
 * continuam tratados à parte em SandboxViewModel.submitThreadInput — eles têm prioridade e
 * nunca são sobrescritos por uma entrada do catálogo com o mesmo prefixo de comando.
 */
data class ComandoCatalogo(
    val id: String,
    val slug: String,
    val nome: String,
    val comando: String,
    val categoria: String,
    val descricaoCurta: String,
    val explicacao: String,
    val objetivo: String,
    val sintaxe: String,
    val exemplo: String,
    val aliases: List<String>
)

object ComandosCatalogoLoader {
    fun load(context: Context): List<ComandoCatalogo> {
        val json = context.assets.open("comandos_catalogo.json").bufferedReader().use { it.readText() }
        val root = JSONObject(json)
        val comandosJson = root.getJSONArray("commands")
        return buildList {
            for (index in 0 until comandosJson.length()) {
                val obj = comandosJson.getJSONObject(index)
                if (!obj.optBoolean("ativo", true)) continue
                val aliasesJson = obj.optJSONArray("aliases")
                val aliases = buildList {
                    if (aliasesJson != null) for (i in 0 until aliasesJson.length()) add(aliasesJson.getString(i))
                }
                add(
                    ComandoCatalogo(
                        id = obj.getString("id"),
                        slug = obj.getString("slug"),
                        nome = obj.getString("nome"),
                        comando = obj.getString("comando"),
                        categoria = obj.optString("categoria", ""),
                        descricaoCurta = obj.optString("descricaoCurta", ""),
                        explicacao = obj.optString("explicacao", ""),
                        objetivo = obj.optString("objetivo", ""),
                        sintaxe = obj.optString("sintaxe", obj.getString("comando")),
                        exemplo = obj.optString("exemplo", ""),
                        aliases = aliases
                    )
                )
            }
        }
    }

    /** Indexa por comando e por alias, ambos normalizados em minúsculas e com "/" na frente. */
    fun indexar(comandos: List<ComandoCatalogo>): Map<String, ComandoCatalogo> = buildMap {
        comandos.forEach { c ->
            put(c.comando.lowercase(), c)
            c.aliases.forEach { alias ->
                val normalizado = (if (alias.startsWith("/")) alias else "/$alias").lowercase()
                putIfAbsent(normalizado, c)
            }
        }
    }
}
