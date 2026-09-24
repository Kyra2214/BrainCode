package com.brain.prompt

import java.io.File
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/** Biblioteca local com busca, versionamento, deduplicação e aprendizado persistente. */
class InMemoryPromptLibrary(
    templatesIniciais: List<PromptTemplate>,
    storageFile: File? = null
) : PromptLibrary, PromptLibrarySnapshot {
    private data class Contador(val sucesso: Int = 0, val falha: Int = 0, val custoTotal: Double = 0.0, val tempoTotalMs: Long = 0)
    private val templates = ConcurrentHashMap<String, PromptTemplate>()
    private val contadores = ConcurrentHashMap<String, Contador>()
    private val file = storageFile ?: defaultStorageFile()
    private val statsFile = File(file.parentFile ?: File("."), "${file.name}.stats")

    /** Seeds como chegaram (mesmas instâncias). Só o que difere delas vai para o disco: com a
     *  biblioteca SQL (~14 mil seeds) regravar os inalterados a cada resultado registrado custaria
     *  dezenas de MB por escrita. Um seed inalterado é recarregado do próprio SQL a cada partida. */
    private val sementes: Map<String, PromptTemplate> = templatesIniciais.associateBy { it.id }

    /** Palavras-chave (contexto + finalidade + skill) já tokenizadas, por template. Com ~14 mil
     *  templates, tokenizar de novo a cada busca/deduplicação era o custo dominante. A entrada só vale
     *  enquanto [origem] for a mesma instância guardada em [templates] (todo update cria uma cópia). */
    private class PalavrasChave(val origem: PromptTemplate, val chaves: Set<String>)
    private val palavrasChave = ConcurrentHashMap<String, PalavrasChave>()

    init {
        val persisted = loadPersisted()
        templatesIniciais.forEach { seed -> templates[seed.id] = persisted[seed.id]?.takeIf { it.versao >= seed.versao } ?: seed }
        persisted.forEach { (id, template) -> if (!templates.containsKey(id)) templates[id] = template }
        loadCounters()
        rebuildCountersFromPersisted()
    }

    override suspend fun buscarPorContexto(contextoDeUso: String): List<PromptTemplate> = buscarPorContextoSnapshot(contextoDeUso)

    override fun buscarPorContextoSnapshot(contextoDeUso: String): List<PromptTemplate> {
        val pedido = PromptSimilarity.tokenize(contextoDeUso)
        if (pedido.isEmpty()) return emptyList()
        return templates.values.asSequence().filterNot { it.aposentado }.map { it to relevancia(it, pedido) }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<PromptTemplate, Int>> { it.second }.thenByDescending { it.first.amostrasObservadas }.thenByDescending { it.first.taxaSucessoEfetiva() })
            .map { it.first }
            .toList()
    }

    fun snapshotTemplates(): List<PromptTemplate> = templates.values.toList()

    override suspend fun salvarNovaVersao(template: PromptTemplate): String {
        // Duplicata = mesmo assunto (palavras-chave parecidas, barato, com cache) E conteúdo quase igual
        // (texto completo, caro — só para os poucos candidatos que passam no pré-filtro).
        val chavesNovo = tokenizarPalavrasChave(template)
        val textoNovo = "${template.finalidade} ${template.contextoDeUso} ${template.textoTemplate}"
        val similar = templates.values.asSequence().filter { it.id != template.id }
            .filter { jaccard(palavrasChaveDe(it), chavesNovo) >= LIMIAR_PALAVRAS_CHAVE }
            .map { it to PromptSimilarity.contentSimilarity("${it.finalidade} ${it.contextoDeUso} ${it.textoTemplate}", textoNovo) }
            .filter { it.second >= DUPLICATE_THRESHOLD }.maxByOrNull { it.second }?.first
        val atual = templates[template.id]
        val finalTemplate = when {
            similar != null -> template.copy(
                id = similar.id,
                versao = maxOf(template.versao, similar.versao + 1),
                taxaSucesso = if (template.amostrasObservadas == 0) similar.taxaSucesso else template.taxaSucesso,
                custoMedio = if (template.amostrasObservadas == 0) similar.custoMedio else template.custoMedio,
                tempoMedioMs = if (template.amostrasObservadas == 0) similar.tempoMedioMs else template.tempoMedioMs,
                amostrasObservadas = if (template.amostrasObservadas == 0) similar.amostrasObservadas else template.amostrasObservadas,
                historicoMelhorias = (similar.historicoMelhorias + template.historicoMelhorias).distinct()
            )
            atual != null -> template.copy(versao = maxOf(template.versao, atual.versao + 1))
            else -> template
        }
        if (similar != null && similar.id != finalTemplate.id) {
            contadores[finalTemplate.id] = contadores[similar.id] ?: Contador()
        }
        templates[finalTemplate.id] = finalTemplate
        persist()
        return finalTemplate.id
    }

    override suspend fun registrarResultado(templateId: String, sucesso: Boolean, custo: Double, tempoMs: Long) {
        val atual = templates[templateId] ?: return
        val contador = contadores.compute(templateId) { _, old ->
            val base = old ?: contadorInicial(atual)
            base.copy(
                sucesso = base.sucesso + if (sucesso) 1 else 0,
                falha = base.falha + if (sucesso) 0 else 1,
                custoTotal = base.custoTotal + custo,
                tempoTotalMs = base.tempoTotalMs + tempoMs
            )
        } ?: return
        atualizarTemplateComContador(atual, contador)
        persist()
        persistCounters()
    }

    private fun contadorInicial(template: PromptTemplate): Contador {
        val total = template.amostrasObservadas.coerceAtLeast(0)
        if (total == 0) return Contador()
        val ok = kotlin.math.round(template.taxaSucesso * total).toInt().coerceIn(0, total)
        return Contador(ok, total - ok, template.custoMedio * total, template.tempoMedioMs * total)
    }

    private fun atualizarTemplateComContador(template: PromptTemplate, contador: Contador) {
        val total = contador.sucesso + contador.falha
        if (total <= 0) return
        val novaTaxa = contador.sucesso.toDouble() / total
        val podaAutomatica = total >= LIMIAR_PODA_AMOSTRAS && novaTaxa < LIMIAR_PODA_TAXA_SUCESSO
        templates[template.id] = template.copy(
            taxaSucesso = novaTaxa,
            custoMedio = contador.custoTotal / total,
            tempoMedioMs = contador.tempoTotalMs / total,
            amostrasObservadas = total,
            historicoMelhorias = (template.historicoMelhorias + "resultado=${if (contador.sucesso > 0) "sucesso" else "falha"}").takeLast(MAX_HISTORY),
            aposentado = template.aposentado || podaAutomatica
        )
    }

    override suspend fun aposentar(templateId: String): Boolean {
        val atual = templates[templateId] ?: return false
        if (!atual.aposentado) { templates[templateId] = atual.copy(aposentado = true); persist() }
        return true
    }

    private fun loadCounters() = runCatching {
        if (!statsFile.exists()) return@runCatching
        statsFile.readLines().forEach { line ->
            val fields = line.split('\t')
            if (fields.size != 5) return@forEach
            val id = decode(fields[0])
            contadores[id] = Contador(fields[1].toInt(), fields[2].toInt(), fields[3].toDouble(), fields[4].toLong())
        }
    }

    private fun rebuildCountersFromPersisted() {
        templates.values.forEach { template ->
            if (contadores.containsKey(template.id)) {
                val counter = contadores[template.id] ?: return@forEach
                atualizarTemplateComContador(template, counter)
                return@forEach
            }
            val history = template.historicoMelhorias
            val successes = history.count { it.startsWith("resultado=sucesso;") }
            val failures = history.count { it.startsWith("resultado=falha;") }
            val total = successes + failures
            if (total > 0) {
                val custo = history.sumOf { valueAfter(it, "custo=") }
                val tempo = history.sumOf { valueAfterLong(it, "tempoMs=") }
                contadores[template.id] = Contador(successes, failures, custo, tempo)
            } else if (template.amostrasObservadas > 0) {
                contadores[template.id] = contadorInicial(template)
            }
        }
    }

    private fun valueAfter(text: String, key: String): Double = text.substringAfter(key, "").substringBefore(';').toDoubleOrNull() ?: 0.0
    private fun valueAfterLong(text: String, key: String): Long = text.substringAfter(key, "").substringBefore(';').toLongOrNull() ?: 0L
    private fun tokenizarPalavrasChave(template: PromptTemplate): Set<String> =
        PromptSimilarity.tokenize("${template.contextoDeUso} ${template.finalidade} ${template.skillRelacionada.orEmpty()}")

    private fun palavrasChaveDe(template: PromptTemplate): Set<String> {
        val emCache = palavrasChave[template.id]
        if (emCache != null && emCache.origem === template) return emCache.chaves
        val chaves = tokenizarPalavrasChave(template)
        palavrasChave[template.id] = PalavrasChave(template, chaves)
        return chaves
    }

    private fun jaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val comuns = a.count { it in b }
        return comuns.toDouble() / (a.size + b.size - comuns)
    }

    private fun relevancia(template: PromptTemplate, pedido: Set<String>): Int {
        val searchable = palavrasChaveDe(template)
        return pedido.count { token -> searchable.any { it == token || it.contains(token) || token.contains(it) } }
    }
    private fun loadPersisted(): Map<String, PromptTemplate> = runCatching {
        if (!file.exists()) return@runCatching emptyMap()
        file.readLines().mapNotNull { line ->
            val fields = line.split('\t'); if (fields.size !in 11..13) return@mapNotNull null
            runCatching {
                val d = fields.map(::decode)
                PromptTemplate(d[0], d[1].toInt(), d[2], d[3], d[4].takeIf(String::isNotEmpty), d[5].takeIf(String::isNotEmpty), d[6], d[7].toDouble(), d[8].toDouble(), d[9].toLong(), d[10].split("\u001f").filter(String::isNotEmpty), d.getOrNull(11)?.toIntOrNull() ?: 0, d.getOrNull(12)?.toBoolean() ?: false)
            }.getOrNull()
        }.associateBy { it.id }
    }.getOrDefault(emptyMap())

    private fun persist() = runCatching {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile ?: File("."), "${file.name}.tmp")
        tmp.writeText(templates.values.filter { sementes[it.id] != it }.joinToString("\n") { t -> listOf(t.id, t.versao.toString(), t.finalidade, t.contextoDeUso, t.skillRelacionada.orEmpty(), t.agenteRelacionado.orEmpty(), t.textoTemplate, t.taxaSucesso.toString(), t.custoMedio.toString(), t.tempoMedioMs.toString(), t.historicoMelhorias.joinToString("\u001f"), t.amostrasObservadas.toString(), t.aposentado.toString()).joinToString("\t", transform = ::encode) })
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    private fun persistCounters() = runCatching {
        statsFile.parentFile?.mkdirs()
        val tmp = File(statsFile.parentFile ?: File("."), "${statsFile.name}.tmp")
        tmp.writeText(contadores.entries.joinToString("\n") { (id, c) -> listOf(encode(id), c.sucesso.toString(), c.falha.toString(), c.custoTotal.toString(), c.tempoTotalMs.toString()).joinToString("\t") })
        if (!tmp.renameTo(statsFile)) { statsFile.delete(); tmp.renameTo(statsFile) }
    }

    private fun encode(value: String): String = Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))
    private fun decode(value: String): String = String(Base64.getDecoder().decode(value), Charsets.UTF_8)
    companion object {
        private const val STORAGE_NAME = "brain-prompt-library.db"
        private const val DUPLICATE_THRESHOLD = 0.90
        /** Pré-filtro da deduplicação: similaridade mínima das palavras-chave para valer a comparação do texto completo. */
        private const val LIMIAR_PALAVRAS_CHAVE = 0.5
        private const val MAX_HISTORY = 100
        /** Poda automática: amostras mínimas antes de considerar o histórico confiável o
         *  suficiente pra aposentar, e taxa de sucesso abaixo da qual isso acontece. */
        private const val LIMIAR_PODA_AMOSTRAS = 5
        private const val LIMIAR_PODA_TAXA_SUCESSO = 0.2

        private fun defaultStorageFile(): File {
            val androidFilesDir = runCatching {
                val activityThread = Class.forName("android.app.ActivityThread")
                val application = activityThread.getMethod("currentApplication").invoke(null)
                application?.javaClass?.getMethod("getFilesDir")?.invoke(application) as? File
            }.getOrNull()
            return androidFilesDir?.let { File(it, "brain/$STORAGE_NAME") }
                ?: File(System.getProperty("java.io.tmpdir") ?: ".", STORAGE_NAME)
        }
    }
}
