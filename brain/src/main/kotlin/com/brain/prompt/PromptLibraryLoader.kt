package com.brain.prompt

import org.json.JSONArray
import org.json.JSONObject

/**
 * Carrega o seed real reaproveitado do IaBrain (assets/prompts_biblioteca.json,
 * 75 prompts já catalogados por categoria/subcaso/tags) e uma expansão local
 * adicional. O catálogo continua offline e determinístico.
 *
 * O JSON tem campos (categoria, subcaso, tags, melhor_para) que não existem
 * como propriedades separadas em PromptTemplate — são dobrados dentro de
 * contextoDeUso como texto pesquisável, porque é isso que
 * InMemoryPromptLibrary.buscarPorContexto() usa pra achar template por
 * tokens, do mesmo jeito que o PromptLibraryService.requireTemplate()
 * original comparava tags com os tokens do pedido.
 */
object PromptLibraryLoader {

    private const val TAXA_SUCESSO_INICIAL = 0.6
    private const val EXPANSION_RESOURCE = "catalog/prompts_biblioteca_expansao.json"
    private const val EXPANSION_ANDROID_ASSET = "prompts_biblioteca_expansao.json"

    fun fromJson(json: String): List<PromptTemplate> {
        val resultado = mutableListOf<PromptTemplate>()
        resultado += parse(json)
        resultado += loadBundledExpansion()
        return resultado.distinctBy { it.id }
    }

    private fun parse(json: String): List<PromptTemplate> {
        val root = JSONObject(json)
        val prompts = root.getJSONArray("prompts")
        val resultado = mutableListOf<PromptTemplate>()

        for (i in 0 until prompts.length()) {
            val item = prompts.getJSONObject(i)
            resultado += PromptTemplate(
                id = item.getString("id"),
                versao = 1,
                finalidade = item.optString("objetivo", item.optString("titulo", "")),
                contextoDeUso = contextoDeUso(item),
                skillRelacionada = skillRelacionada(item),
                agenteRelacionado = agentesRelacionados(item.optJSONArray("melhor_para")),
                textoTemplate = item.getString("template"),
                taxaSucesso = TAXA_SUCESSO_INICIAL,
                custoMedio = 0.0,
                tempoMedioMs = 0L,
                historicoMelhorias = emptyList()
            )
        }
        return resultado
    }

    /**
     * Primeiro tenta classpath, que cobre testes/JVM. Em Android, tenta o
     * AssetManager da aplicação sem introduzir dependência Android no build
     * do módulo brain. Falha silenciosamente para manter compatibilidade com
     * ambientes que só possuem o seed original.
     */
    private fun loadBundledExpansion(): List<PromptTemplate> = runCatching {
        val classpathJson = Thread.currentThread().contextClassLoader
            ?.getResourceAsStream(EXPANSION_RESOURCE)
            ?.bufferedReader()
            ?.use { it.readText() }
        if (!classpathJson.isNullOrBlank()) return@runCatching parse(classpathJson)

        val activityThread = Class.forName("android.app.ActivityThread")
        val application = activityThread.getMethod("currentApplication").invoke(null) ?: return@runCatching emptyList()
        val assets = application.javaClass.getMethod("getAssets").invoke(application)
        val stream = assets.javaClass.getMethod("open", String::class.java).invoke(assets, EXPANSION_ANDROID_ASSET)
        val text = stream.javaClass.getMethod("readBytes").invoke(stream) as ByteArray
        stream.javaClass.getMethod("close").invoke(stream)
        parse(String(text, Charsets.UTF_8))
    }.getOrDefault(emptyList())

    private fun contextoDeUso(item: JSONObject): String {
        val categoria = item.optString("categoria", "")
        val subcaso = item.optString("subcaso", "")
        val descricao = item.optString("descricao_curta", "")
        val tags = jsonArrayToList(item.optJSONArray("tags")).joinToString(" ")
        return listOf(categoria, subcaso, descricao, tags).filter { it.isNotBlank() }.joinToString(" | ")
    }

    private fun agentesRelacionados(melhorPara: JSONArray?): String? {
        val lista = jsonArrayToList(melhorPara)
        return lista.ifEmpty { null }?.joinToString(", ")
    }

    /** Resolve a Skill declarativa por categoria e tags do seed, sem conceder autorização. */
    private fun skillRelacionada(item: JSONObject): String? {
        val texto = listOf(
            item.optString("categoria"), item.optString("subcaso"),
            item.optString("objetivo"), item.optString("titulo"),
            jsonArrayToList(item.optJSONArray("tags")).joinToString(" ")
        ).joinToString(" ").lowercase()
        return when {
            listOf("código", "codigo", "program", "software", "desenvolv").any(texto::contains) -> "code.generation"
            listOf("pesquis", "web", "fonte", "investig").any(texto::contains) -> "research.web"
            listOf("segurança", "seguranca", "vulnerab").any(texto::contains) -> "security.audit"
            listOf("planej", "arquitet", "decompos").any(texto::contains) -> "planning.decomposition"
            listOf("resum", "document", "texto").any(texto::contains) -> "document.summarization"
            else -> null
        }
    }

    private fun jsonArrayToList(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return (0 until array.length()).map { array.getString(it) }
    }
}
