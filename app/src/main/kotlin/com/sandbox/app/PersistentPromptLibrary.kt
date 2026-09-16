package com.sandbox.app

import com.brain.prompt.InMemoryPromptLibrary
import com.brain.prompt.PromptLibrary
import com.brain.prompt.PromptLibrarySnapshot
import com.brain.prompt.PromptTemplate
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistência local da biblioteca de prompts.
 * Mantém o motor de busca do Brain e grava templates/resultados em disco,
 * sobrevivendo ao fechamento e reabertura do aplicativo.
 */
class PersistentPromptLibrary(
    seed: List<PromptTemplate>,
    private val file: File
) : PromptLibrary, PromptLibrarySnapshot {

    private val delegate = InMemoryPromptLibrary(load(seed))

    override suspend fun buscarPorContexto(contextoDeUso: String): List<PromptTemplate> =
        delegate.buscarPorContexto(contextoDeUso)

    override fun buscarPorContextoSnapshot(contextoDeUso: String): List<PromptTemplate> =
        delegate.buscarPorContextoSnapshot(contextoDeUso)

    override suspend fun salvarNovaVersao(template: PromptTemplate) {
        val existentes = delegate.buscarPorContextoSnapshot("${template.contextoDeUso} ${template.finalidade}")
        val duplicado = existentes.firstOrNull { similaridade(it, template) >= DUPLICATE_THRESHOLD }
        if (duplicado != null) {
            delegate.salvarNovaVersao(
                template.copy(
                    id = duplicado.id,
                    versao = duplicado.versao + 1,
                    historicoMelhorias = (duplicado.historicoMelhorias + template.historicoMelhorias).distinct()
                )
            )
        } else {
            delegate.salvarNovaVersao(template)
        }
        persist()
    }

    override suspend fun registrarResultado(templateId: String, sucesso: Boolean, custo: Double, tempoMs: Long) {
        delegate.registrarResultado(templateId, sucesso, custo, tempoMs)
        persist()
    }

    private fun load(seed: List<PromptTemplate>): List<PromptTemplate> {
        if (!file.exists()) return seed
        return try {
            val saved = JSONArray(file.readText())
            val merged = LinkedHashMap<String, PromptTemplate>()
            seed.forEach { merged[it.id] = it }
            for (i in 0 until saved.length()) {
                val template = fromJson(saved.getJSONObject(i))
                val atual = merged[template.id]
                if (atual == null || template.versao >= atual.versao) merged[template.id] = template
            }
            merged.values.toList()
        } catch (_: Exception) {
            seed
        }
    }

    private fun persist() {
        file.parentFile?.mkdirs()
        val array = JSONArray()
        delegate.snapshotTemplates().forEach { array.put(toJson(it)) }
        file.writeText(array.toString())
    }

    private fun similaridade(a: PromptTemplate, b: PromptTemplate): Double {
        val left = tokenize("${a.finalidade} ${a.contextoDeUso} ${a.textoTemplate}")
        val right = tokenize("${b.finalidade} ${b.contextoDeUso} ${b.textoTemplate}")
        if (left.isEmpty() || right.isEmpty()) return 0.0
        return left.intersect(right).size.toDouble() / left.union(right).size.toDouble()
    }

    private fun tokenize(value: String): Set<String> = value.lowercase()
        .split(Regex("[^\\p{L}\\p{N}]+"))
        .filter { it.length > 2 }
        .toSet()

    private fun toJson(t: PromptTemplate) = JSONObject().apply {
        put("id", t.id)
        put("versao", t.versao)
        put("finalidade", t.finalidade)
        put("contextoDeUso", t.contextoDeUso)
        put("skillRelacionada", t.skillRelacionada)
        put("agenteRelacionado", t.agenteRelacionado)
        put("textoTemplate", t.textoTemplate)
        put("taxaSucesso", t.taxaSucesso)
        put("custoMedio", t.custoMedio)
        put("tempoMedioMs", t.tempoMedioMs)
        put("historicoMelhorias", JSONArray(t.historicoMelhorias))
    }

    private fun fromJson(o: JSONObject) = PromptTemplate(
        id = o.getString("id"),
        versao = o.optInt("versao", 1),
        finalidade = o.optString("finalidade"),
        contextoDeUso = o.optString("contextoDeUso"),
        skillRelacionada = o.optString("skillRelacionada").takeIf { it.isNotBlank() && it != "null" },
        agenteRelacionado = o.optString("agenteRelacionado").takeIf { it.isNotBlank() && it != "null" },
        textoTemplate = o.optString("textoTemplate"),
        taxaSucesso = o.optDouble("taxaSucesso", 0.5),
        custoMedio = o.optDouble("custoMedio", 0.0),
        tempoMedioMs = o.optLong("tempoMedioMs", 0L),
        historicoMelhorias = buildList {
            val history = o.optJSONArray("historicoMelhorias") ?: return@buildList
            for (i in 0 until history.length()) add(history.optString(i))
        }
    )

    companion object {
        private const val DUPLICATE_THRESHOLD = 0.90
    }
}
