package com.brain.memory

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant

/** Append-only local memory with atomic snapshot recovery and idempotent experience IDs. */
class FileExperienceMemory(private val file: File) : ExperienceMemory {
    private val lock = Any()
    private val experiencias = linkedMapOf<String, Experiencia>()

    init {
        synchronized(lock) { load() }
    }

    override suspend fun registrar(experiencia: Experiencia) {
        synchronized(lock) {
            if (experiencias.containsKey(experiencia.id)) return
            require(experiencia.problema.none { it == '\u0000' }) { "problema contém caractere inválido" }
            experiencias[experiencia.id] = experiencia
            append(experiencia)
        }
    }

    override suspend fun buscarPorTarefa(tarefaId: String): List<Experiencia> = synchronized(lock) {
        experiencias.values.filter { it.tarefaId == tarefaId }
    }

    override suspend fun taxaSucessoPorEstrategia(estrategia: String): Double = synchronized(lock) {
        val relevantes = experiencias.values.filter { it.estrategiaUsada == estrategia }
        if (relevantes.isEmpty()) return@synchronized 0.5
        relevantes.sumOf {
            when (it.resultado) {
                ResultadoExperiencia.SUCESSO -> 1.0
                ResultadoExperiencia.CORRIGIDO_APOS_FALHA -> 0.5
                ResultadoExperiencia.FALHA -> 0.0
            }
        } / relevantes.size
    }

    private fun load() {
        if (!file.exists()) return
        file.readLines().forEach { line -> runCatching { fromJson(JSONObject(line)) }.getOrNull()?.let { experiencias[it.id] = it } }
    }

    private fun append(experiencia: Experiencia) {
        file.parentFile?.mkdirs()
        file.appendText(toJson(experiencia).toString() + "\n")
    }

    private fun toJson(e: Experiencia) = JSONObject().apply {
        put("id", e.id); put("tarefaId", e.tarefaId); put("problema", e.problema)
        put("estrategiaUsada", e.estrategiaUsada); put("promptUsado", e.promptUsado ?: JSONObject.NULL)
        put("resultado", e.resultado.name); put("custoEstimado", e.custoEstimado ?: JSONObject.NULL)
        put("tempoTotalMs", e.tempoTotalMs); put("erros", JSONArray(e.erros)); put("registradoEm", e.registradoEm.toString())
    }

    private fun fromJson(json: JSONObject) = Experiencia(
        id = json.getString("id"), tarefaId = json.getString("tarefaId"), problema = json.getString("problema"),
        estrategiaUsada = json.getString("estrategiaUsada"), promptUsado = json.optString("promptUsado").takeUnless { it == "null" },
        resultado = ResultadoExperiencia.valueOf(json.getString("resultado")),
        custoEstimado = if (json.isNull("custoEstimado")) null else json.getDouble("custoEstimado"),
        tempoTotalMs = json.getLong("tempoTotalMs"),
        erros = (0 until json.optJSONArray("erros").length()).map { json.getJSONArray("erros").getString(it) },
        registradoEm = Instant.parse(json.getString("registradoEm"))
    )
}
