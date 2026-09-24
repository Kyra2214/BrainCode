package com.brain.prompt

import java.io.Reader

/**
 * Carrega a biblioteca de prompts a partir do SQL (assets/prompts_library.sql) — a única
 * origem da biblioteca no runtime.
 *
 * O arquivo é SQL padrão (DROP/CREATE/INSERT, com literais entre aspas simples e `''` como
 * escape). Este loader NÃO executa o SQL: ele lê os comandos em streaming, extrai só os
 * INSERTs das tabelas de prompt e das tabelas auxiliares (categorias e tags) e converte cada
 * prompt em [PromptTemplate]. Isso mantém o módulo :brain em Kotlin/JVM puro.
 *
 * O texto do prompt vai intacto em [PromptTemplate.textoTemplate] (com os `[PLACEHOLDER]` ou
 * `{{var}}` da fonte). Categoria, seção, título, descrição e tags são dobrados, de forma
 * curta, em [PromptTemplate.contextoDeUso] — é isso que InMemoryPromptLibrary.buscarPorContexto()
 * e PromptSimilarity.compatibility() comparam com o pedido. O corpo do prompt fica de fora de
 * propósito: com ~14 mil prompts, indexar o corpo inteiro tornaria toda busca lenta.
 *
 * Ids: `sql:<tabela>:<id>` (estáveis entre execuções; as estatísticas persistidas dependem disso).
 * Linhas sem texto de prompt são ignoradas.
 */
object PromptLibraryLoader {

    /** Arquivo em app/src/main/assets. */
    const val ASSET_NAME = "prompts_library.sql"

    private const val TAXA_SUCESSO_INICIAL = 0.6 // seed ainda sem uso real: neutro-otimista até haver dado real
    private const val MAX_FINALIDADE = 120
    private const val MAX_PARTE_CONTEXTO = 160
    private const val MAX_CONTEXTO = 500
    private const val BUFFER_LEITURA = 64 * 1024
    private const val TAMANHO_MAX_CABECALHO = 4096

    private val ESPACOS = Regex("\\s+")
    private val CABECALHO_INSERT = Regex(
        "\\A\\s*INSERT\\s+INTO\\s+(\\w+)\\s*\\(([^)]*)\\)\\s*VALUES\\s*",
        RegexOption.IGNORE_CASE
    )

    /** Tabelas cujas linhas viram PromptTemplate. */
    private val TABELAS_DE_PROMPT = setOf(
        "prompts", "money_prompts", "coding_prompts", "finance_prompts", "crafti_prompts",
        "gallery_prompts", "everything_prompts", "llmprompts_prompts", "awesome_prompts",
        "aiprompts2026_prompts", "promptforge_prompts", "promptschat_prompts", "extra_prompts"
    )

    /** Tabela de categorias -> coluna com o nome da categoria. */
    private val COLUNA_NOME_CATEGORIA = mapOf(
        "situations" to "name",
        "money_sections" to "name",
        "coding_categories" to "name",
        "finance_chapters" to "title",
        "crafti_categories" to "name",
        "everything_categories" to "name"
    )

    /** Tabela de prompts -> (tabela de categorias, coluna de prompts que aponta para ela). */
    private val CATEGORIA_DO_PROMPT = mapOf(
        "prompts" to Pair("situations", "situation_id"),
        "money_prompts" to Pair("money_sections", "section_id"),
        "coding_prompts" to Pair("coding_categories", "category_id"),
        "finance_prompts" to Pair("finance_chapters", "chapter_id"),
        "crafti_prompts" to Pair("crafti_categories", "category_id"),
        "everything_prompts" to Pair("everything_categories", "category_slug")
    )

    /** Tabela de prompts -> tabela de tags (colunas prompt_id, tag). */
    private val TAGS_DO_PROMPT = mapOf(
        "gallery_prompts" to "gallery_tags",
        "awesome_prompts" to "awesome_tags",
        "promptforge_prompts" to "promptforge_tags"
    )

    private val TABELAS_DE_TAGS = TAGS_DO_PROMPT.values.toSet()

    /** Skill declarativa por palavra-chave nos metadados (nunca no corpo do prompt); não concede autorização. */
    private val SKILLS_POR_PALAVRA_CHAVE = listOf(
        "code.generation" to listOf("código", "codigo", "program", "software", "desenvolv", "coding", "code", "developer", "debug", "refactor"),
        "research.web" to listOf("pesquis", "web", "fonte", "investig", "research"),
        "security.audit" to listOf("segurança", "seguranca", "vulnerab", "security"),
        "planning.decomposition" to listOf("planej", "arquitet", "decompos", "planning", "architect"),
        "document.summarization" to listOf("resum", "document", "texto", "summar")
    )

    /** Lê o SQL inteiro (streaming) e devolve os templates. O [reader] é lido até o fim, mas não é fechado. */
    fun fromSql(reader: Reader): List<PromptTemplate> {
        val catalogo = Catalogo()
        lerComandos(reader) { comando -> registrarInsert(comando, catalogo) }
        return catalogo.prompts.mapNotNull { linha -> paraTemplate(linha, catalogo) }
    }

    // ------------------------------------------------------------------ leitura do SQL

    private class Linha(
        val tabela: String,
        colunas: Map<String, Int>,
        valores: List<String?>
    ) {
        /*
         * INSERTs do seed possuem colunas auxiliares grandes que nunca entram no
         * PromptTemplate. Não conservar a lista inteira reduz drasticamente o
         * pico de heap no Android, sem mudar o contrato do loader.
         */
        private val slots = SLOTS_POR_TABELA[tabela].orEmpty()
        private val valoresNecessarios = arrayOfNulls<String>(slots.size).also { compactos ->
            slots.forEach { (coluna, slot) ->
                compactos[slot] = colunas[coluna]?.let { valores.getOrNull(it) }?.takeIf { it.isNotBlank() }
            }
        }

        /** Valor da coluna, ou null se a coluna não existe, é NULL ou está em branco. */
        operator fun get(coluna: String): String? = slots[coluna]?.let { valoresNecessarios[it] }

        companion object {
            private val COLUNAS_POR_TABELA = mapOf(
                "prompts" to setOf("id", "situation_id", "title", "prompt_text"),
                "money_prompts" to setOf("id", "section_id", "title", "prompt_text", "context_note"),
                "coding_prompts" to setOf("id", "category_id", "title", "prompt_text", "use_when"),
                "finance_prompts" to setOf("id", "chapter_id", "section", "title", "prompt_text"),
                "crafti_prompts" to setOf("id", "category_id", "prompt_text"),
                "gallery_prompts" to setOf("id", "title", "category", "prompt_text"),
                "everything_prompts" to setOf("id", "category_slug", "section", "title", "prompt_text"),
                "llmprompts_prompts" to setOf("id", "title", "description", "system_prompt", "user_prompt"),
                "awesome_prompts" to setOf("id", "title", "category", "prompt_text", "description"),
                "aiprompts2026_prompts" to setOf("id", "category_title", "title", "prompt_text", "best_for"),
                "promptforge_prompts" to setOf("id", "category", "title", "content", "description", "best_for"),
                "promptschat_prompts" to setOf("id", "title", "prompt_text", "type"),
                "extra_prompts" to setOf("id", "area", "title", "prompt_text"),
                "situations" to setOf("id", "slug", "name"),
                "money_sections" to setOf("id", "slug", "name"),
                "coding_categories" to setOf("id", "slug", "name"),
                "finance_chapters" to setOf("id", "slug", "title"),
                "crafti_categories" to setOf("id", "slug", "name"),
                "everything_categories" to setOf("id", "slug", "name"),
                "gallery_tags" to setOf("prompt_id", "tag"),
                "awesome_tags" to setOf("prompt_id", "tag"),
                "promptforge_tags" to setOf("prompt_id", "tag")
            )

            private val SLOTS_POR_TABELA: Map<String, Map<String, Int>> = COLUNAS_POR_TABELA.mapValues { (_, colunas) ->
                colunas.withIndex().associate { it.value to it.index }
            }
        }
    }

    private class Catalogo {
        val prompts = ArrayList<Linha>()

        /** tabela de categorias -> (chave -> nome) */
        val categorias = HashMap<String, MutableMap<String, String>>()

        /** tabela de tags -> (prompt_id -> tags) */
        val tags = HashMap<String, MutableMap<String, MutableList<String>>>()
    }

    /**
     * Divide o SQL em comandos terminados por `;`, ignorando `;` e `--` dentro de literais
     * entre aspas simples e descartando comentários de linha (`-- ...`). Comentários `/* */`
     * não são suportados (o arquivo não os usa).
     *
     * Um `''` (aspas escapada) apenas fecha e reabre o literal em seguida, então alternar o
     * estado a cada `'` é suficiente para achar o fim dos comandos.
     */
    private fun lerComandos(reader: Reader, aoLerComando: (String) -> Unit) {
        val atual = StringBuilder()
        val buffer = CharArray(BUFFER_LEITURA)
        var emTexto = false
        var emComentario = false
        var hifenAnterior = false
        while (true) {
            val lidos = reader.read(buffer)
            if (lidos < 0) break
            for (k in 0 until lidos) {
                val c = buffer[k]
                when {
                    emComentario -> {
                        if (c == '\n') {
                            emComentario = false
                            atual.append('\n')
                        }
                    }
                    emTexto -> {
                        atual.append(c)
                        if (c == '\'') emTexto = false
                    }
                    c == '\'' -> {
                        atual.append(c)
                        emTexto = true
                        hifenAnterior = false
                    }
                    c == '-' && hifenAnterior -> {
                        atual.setLength(atual.length - 1)
                        emComentario = true
                        hifenAnterior = false
                    }
                    c == '-' -> {
                        atual.append(c)
                        hifenAnterior = true
                    }
                    c == ';' -> {
                        emitir(atual, aoLerComando)
                        hifenAnterior = false
                    }
                    else -> {
                        atual.append(c)
                        hifenAnterior = false
                    }
                }
            }
        }
        emitir(atual, aoLerComando)
    }

    private fun emitir(atual: StringBuilder, aoLerComando: (String) -> Unit) {
        if (atual.isNotBlank()) aoLerComando(atual.toString())
        atual.setLength(0)
    }

    private fun registrarInsert(comando: String, catalogo: Catalogo) {
        val cabecalho = CABECALHO_INSERT.find(comando.take(TAMANHO_MAX_CABECALHO)) ?: return
        val tabela = cabecalho.groupValues[1].lowercase()
        val ehPrompt = tabela in TABELAS_DE_PROMPT
        val ehCategoria = tabela in COLUNA_NOME_CATEGORIA
        val ehTag = tabela in TABELAS_DE_TAGS
        if (!ehPrompt && !ehCategoria && !ehTag) return

        val nomes = cabecalho.groupValues[2].split(',').map { it.trim().lowercase() }
        val indice = HashMap<String, Int>()
        nomes.forEachIndexed { posicao, nome -> indice[nome] = posicao }

        lerLinhas(comando, cabecalho.range.last + 1) { valores ->
            val linha = Linha(tabela, indice, valores)
            when {
                ehPrompt -> catalogo.prompts.add(linha)
                ehCategoria -> {
                    val chave = linha["id"] ?: linha["slug"]
                    val nome = linha[COLUNA_NOME_CATEGORIA.getValue(tabela)]
                    if (chave != null && nome != null) {
                        catalogo.categorias.getOrPut(tabela) { HashMap() }[chave] = nome
                    }
                }
                else -> {
                    val promptId = linha["prompt_id"]
                    val tag = linha["tag"]
                    if (promptId != null && tag != null) {
                        catalogo.tags.getOrPut(tabela) { HashMap() }.getOrPut(promptId) { ArrayList() }.add(tag)
                    }
                }
            }
        }
    }

    /** Lê as tuplas `(v1, v2, ...)` de um INSERT a partir de [inicio] (logo após `VALUES`). */
    private fun lerLinhas(sql: String, inicio: Int, aoLerLinha: (List<String?>) -> Unit) {
        val n = sql.length
        var i = inicio
        while (true) {
            while (i < n && (sql[i].isWhitespace() || sql[i] == ',')) i++
            if (i >= n) return
            check(sql[i] == '(') { "SQL malformado: esperado '(' na posição $i" }
            i++
            val valores = ArrayList<String?>()
            while (true) {
                while (i < n && sql[i].isWhitespace()) i++
                check(i < n) { "SQL malformado: INSERT truncado" }
                if (sql[i] == '\'') {
                    val texto = StringBuilder()
                    i++
                    while (true) {
                        val aspas = sql.indexOf('\'', i)
                        check(aspas >= 0) { "SQL malformado: literal sem aspas de fechamento" }
                        texto.append(sql, i, aspas)
                        if (aspas + 1 < n && sql[aspas + 1] == '\'') {
                            texto.append('\'')
                            i = aspas + 2
                        } else {
                            i = aspas + 1
                            break
                        }
                    }
                    valores.add(texto.toString())
                } else {
                    val inicioToken = i
                    while (i < n && sql[i] != ',' && sql[i] != ')') i++
                    val token = sql.substring(inicioToken, i).trim()
                    valores.add(if (token.equals("NULL", ignoreCase = true)) null else token)
                }
                while (i < n && sql[i].isWhitespace()) i++
                check(i < n) { "SQL malformado: INSERT truncado" }
                val separador = sql[i]
                i++
                if (separador == ')') break
                check(separador == ',') { "SQL malformado: separador inesperado '$separador'" }
            }
            aoLerLinha(valores)
        }
    }

    // ------------------------------------------------------------------ conversão para PromptTemplate

    /** [meta]: partes curtas que descrevem o prompt (categoria, seção, título, descrição...). */
    private class Extraido(val titulo: String?, val texto: String?, val meta: List<String?>)

    private fun paraTemplate(linha: Linha, catalogo: Catalogo): PromptTemplate? {
        val id = linha["id"] ?: return null
        val categoria = CATEGORIA_DO_PROMPT[linha.tabela]?.let { (tabelaCategoria, colunaChave) ->
            linha[colunaChave]?.let { chave -> catalogo.categorias[tabelaCategoria]?.get(chave) }
        }
        val tags = TAGS_DO_PROMPT[linha.tabela]?.let { catalogo.tags[it]?.get(id) }.orEmpty()

        val extraido = when (linha.tabela) {
            "prompts" -> Extraido(linha["title"], linha["prompt_text"], listOf(categoria, linha["title"]))
            "money_prompts" -> Extraido(linha["title"], linha["prompt_text"], listOf(categoria, linha["title"], linha["context_note"]))
            "coding_prompts" -> Extraido(linha["title"], linha["prompt_text"], listOf(categoria, linha["title"], linha["use_when"]))
            "finance_prompts" -> {
                val titulo = linha["title"] ?: linha["section"]
                Extraido(titulo, linha["prompt_text"], listOf(categoria, linha["section"], linha["title"]))
            }
            "crafti_prompts" -> Extraido(null, linha["prompt_text"], listOf(categoria))
            "gallery_prompts" -> Extraido(linha["title"], linha["prompt_text"], listOf(linha["category"], linha["title"]))
            "everything_prompts" -> Extraido(linha["title"], linha["prompt_text"], listOf(categoria, linha["section"], linha["title"]))
            "llmprompts_prompts" -> {
                // Alguns registros só referenciam o system prompt de outro (system_ref); aí só há o user_prompt.
                val texto = listOfNotNull(linha["system_prompt"], linha["user_prompt"]).joinToString("\n\n").ifBlank { null }
                Extraido(linha["title"], texto, listOf(linha["title"], linha["description"]))
            }
            "awesome_prompts" -> Extraido(linha["title"], linha["prompt_text"], listOf(linha["category"], linha["title"], linha["description"]))
            "aiprompts2026_prompts" -> Extraido(linha["title"], linha["prompt_text"], listOf(linha["category_title"], linha["title"], linha["best_for"]))
            "promptforge_prompts" -> Extraido(linha["title"], linha["content"], listOf(linha["category"], linha["title"], linha["description"], linha["best_for"]))
            "promptschat_prompts" -> Extraido(linha["title"], linha["prompt_text"], listOf(linha["title"], linha["type"]))
            "extra_prompts" -> Extraido(linha["title"], linha["prompt_text"], listOf(linha["area"], linha["title"]))
            else -> return null
        }

        val texto = extraido.texto ?: return null
        val partes = (extraido.meta + tags).filterNotNull().map { resumo(it, MAX_PARTE_CONTEXTO) }.filter { it.isNotEmpty() }.distinct()
        val finalidade = resumo(extraido.titulo ?: texto, MAX_FINALIDADE)

        return PromptTemplate(
            id = "sql:${linha.tabela}:$id",
            versao = 1,
            finalidade = finalidade,
            contextoDeUso = partes.joinToString(" | ").take(MAX_CONTEXTO),
            skillRelacionada = skillRelacionada((listOfNotNull(extraido.titulo) + partes).joinToString(" ")),
            agenteRelacionado = null, // o SQL não tem o conceito de agente relacionado
            textoTemplate = texto,
            taxaSucesso = TAXA_SUCESSO_INICIAL,
            custoMedio = 0.0,  // provedores gratuitos, como o resto do catálogo
            tempoMedioMs = 0L, // sem dado real ainda; primeira leva de registrarResultado() ajusta
            historicoMelhorias = emptyList()
        )
    }

    private fun skillRelacionada(metadados: String): String? {
        val texto = metadados.lowercase()
        return SKILLS_POR_PALAVRA_CHAVE.firstOrNull { (_, palavras) -> palavras.any { texto.contains(it) } }?.first
    }

    /** Uma linha só, sem espaços repetidos, cortada em [max] caracteres. */
    private fun resumo(texto: String, max: Int): String {
        val compacto = ESPACOS.replace(texto.take(max * 4), " ").trim()
        return if (compacto.length <= max) compacto else compacto.take(max - 1).trimEnd() + "…"
    }
}
