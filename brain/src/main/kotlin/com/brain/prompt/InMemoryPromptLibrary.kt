package com.brain.prompt

import java.io.File
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * Biblioteca local de prompts com busca contextual, versionamento, deduplicação
 * e persistência em disco. A persistência usa java.io.tmpdir para funcionar
 * tanto no Android quanto nos testes JVM sem acoplar o módulo Brain ao Android.
 */
class InMemoryPromptLibrary(
    templatesIniciais: List<PromptTemplate>
) : PromptLibrary, PromptLibrarySnapshot {

    private data class Contador(
        val sucesso: Int = 0,
        val falha: Int = 0,
        val custoTotal: Double = 0.0,
        val tempoTotalMs: Long = 0
    )

    private val templates = ConcurrentHashMap<String, PromptTemplate>()
    private val contadores = ConcurrentHashMap<String, Contador>()
    private val storageFile = File(System.getProperty("java.io.tmpdir") ?: ".", STORAGE_NAME)

    init {
        val persisted = loadPersisted()
        templatesIniciais.forEach { seed ->
            val saved = persisted[seed.id]
            if (saved == null || saved.versao < seed.versao) templates[seed.id] = seed else templates[seed.id] = saved
        }
        persisted.forEach { (id, template) -> if (!templates.containsKey(id)) templates[id] = template }
    }

    override suspend fun buscarPorContexto(contextoDeUso: String): List<PromptTemplate> =
        buscarPorContextoSnapshot(contextoDeUso)

    override fun buscarPorContextoSnapshot(contextoDeUso: String): List<PromptTemplate> {
        val tokensPedido = tokenizar(contextoDeUso)
        if (tokensPedido.isEmpty()) return emptyList()
        return templates.values
            .map { template -> template to relevancia(template, tokensPedido) }
            .filter { (_, pontuacao) -> pontuacao > 0 }
            .sortedWith(compareByDescending<Pair<PromptTemplate, Int>> { it.second }.thenByDescending { it.first.taxaSucesso })
            .map { it.first }
    }

    fun snapshotTemplates(): List<PromptTemplate> = templates.values.toList()

    override suspend fun salvarNovaVersao(template: PromptTemplate) {
        val similar = templates.values
            .filter { it.id != template.id }
            .map { it to similaridade(it, template) }
            .filter { (_, score) -> score >= DUPLICATE_THRESHOLD }
            .maxByOrNull { (_, score) -> score }
            ?.first

        val finalTemplate = if (similar != null) {
            template.copy(
                id = similar.id,
                versao = maxOf(template.versao, similar.versao + 1),
                taxaSucesso = if (template.taxaSucesso == DEFAULT_GENERATED_SUCCESS) similar.taxaSucesso else template.taxaSucesso,
                historicoMelhorias = (similar.historicoMelhorias + template.historicoMelhorias).distinct()
            )
        } else {
            val atual = templates[template.id]
            if (atual != null) template.copy(versao = maxOf(template.versao, atual.versao + 1)) else template
        }
        templates[finalTemplate.id] = finalTemplate
        persist()
    }

    override suspend fun registrarResultado(templateId: String, sucesso: Boolean, custo: Double, tempoMs: Long) {
        val atual = templates[templateId] ?: return
        val contador = contadores.compute(templateId) { _, existente ->
            val base = existente ?: Contador()
            base.copy(
                sucesso = base.sucesso + if (sucesso) 1 else 0,
                falha = base.falha + if (sucesso) 0 else 1,
                custoTotal = base.custoTotal + custo,
                tempoTotalMs = base.tempoTotalMs + tempoMs
            )
        } ?: return
        val total = contador.sucesso + contador.falha
        templates[templateId] = atual.copy(
            taxaSucesso = contador.sucesso.toDouble() / total,
            custoMedio = contador.custoTotal / total,
            tempoMedioMs = contador.tempoTotalMs / total,
            historicoMelhorias = atual.historicoMelhorias + "resultado=${if (sucesso) "sucesso" else "falha"};custo=$custo;tempoMs=$tempoMs"
        )
        persist()
    }

    private fun relevancia(template: PromptTemplate, tokensPedido: Set<String>): Int {
        val tokensTemplate = tokenizar(template.contextoDeUso) + tokenizar(template.finalidade)
        return tokensPedido.count { token -> tokensTemplate.any { it == token || it.contains(token) || token.contains(it) } }
    }

    private fun similaridade(a: PromptTemplate, b: PromptTemplate): Double {
        val left = tokenizar("${a.finalidade} ${a.contextoDeUso} ${a.textoTemplate}")
        val right = tokenizar("${b.finalidade} ${b.contextoDeUso} ${b.textoTemplate}")
        if (left.isEmpty() || right.isEmpty()) return 0.0
        return left.intersect(right).size.toDouble() / left.union(right).size.toDouble()
    }

    private fun tokenizar(texto: String): Set<String> =
        texto.lowercase()
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length > 2 }
            .toSet()

    private fun loadPersisted(): Map<String, PromptTemplate> = runCatching {
        if (!storageFile.exists()) return@runCatching emptyMap()
        storageFile.readLines().mapNotNull { line ->
            val fields = line.split('\t')
            if (fields.size != 11) return@mapNotNull null
            runCatching {
                val d = fields.map { decode(it) }
                PromptTemplate(
                    id = d[0], versao = d[1].toInt(), finalidade = d[2], contextoDeUso = d[3],
                    skillRelacionada = d[4].takeIf { it.isNotEmpty() }, agenteRelacionado = d[5].takeIf { it.isNotEmpty() },
                    textoTemplate = d[6], taxaSucesso = d[7].toDouble(), custoMedio = d[8].toDouble(),
                    tempoMedioMs = d[9].toLong(), historicoMelhorias = d[10].split("\u001f").filter { it.isNotEmpty() }
                )
            }.getOrNull()
        }.associateBy { it.id }
    }.getOrDefault(emptyMap())

    private fun persist() {
        runCatching {
            storageFile.parentFile?.mkdirs()
            val tmp = File(storageFile.parentFile ?: File("."), "${storageFile.name}.tmp")
            tmp.writeText(templates.values.joinToString("\n") { t ->
                listOf(t.id, t.versao.toString(), t.finalidade, t.contextoDeUso, t.skillRelacionada.orEmpty(), t.agenteRelacionado.orEmpty(), t.textoTemplate, t.taxaSucesso.toString(), t.custoMedio.toString(), t.tempoMedioMs.toString(), t.historicoMelhorias.joinToString("\u001f")).joinToString("\t") { encode(it) }
            })
            if (!tmp.renameTo(storageFile)) {
                storageFile.delete()
                tmp.renameTo(storageFile)
            }
        }
    }

    private fun encode(value: String): String = Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))
    private fun decode(value: String): String = String(Base64.getDecoder().decode(value), Charsets.UTF_8)

    companion object {
        private const val STORAGE_NAME = "brain-prompt-library.db"
        private const val DUPLICATE_THRESHOLD = 0.90
        private const val DEFAULT_GENERATED_SUCCESS = 0.5
    }
}
