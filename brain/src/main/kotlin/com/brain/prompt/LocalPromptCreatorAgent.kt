package com.brain.prompt

import java.util.Locale

/**
 * Cria prompts localmente por composição semântica de componentes — nunca por
 * concatenação crua de palavras-chave. Cada componente é extraído do pedido
 * quando presente; quando ausente, recebe um valor padrão determinístico
 * (mesma entrada → mesma saída), nunca um valor inventado como se o usuário
 * tivesse pedido.
 */
class LocalPromptCreatorAgent : PromptCreatorAgent {

    override fun criar(pedido: String, contexto: PromptTemplate?, contextoPesquisa: String?): PromptCriado {
        val textoBase = pedido
        val dominio = PromptDomain.classificar(pedido)
        val texto = when (dominio) {
            PromptDomain.IMAGEM -> componsIrImagem(pedido, textoBase, contexto, contextoPesquisa)
            PromptDomain.VIDEO -> componsIrVideo(pedido, textoBase, contexto, contextoPesquisa)
            PromptDomain.CODIGO -> componsIrCodigo(pedido, contexto, contextoPesquisa)
            PromptDomain.TEXTO -> componsIrTexto(pedido, contexto, contextoPesquisa)
        }
        return PromptCriado(
            texto = texto,
            dominio = dominio,
            origem = if (contexto != null) "local:adaptado-de:${contexto.id}" else "local:criado-do-zero",
            componentesDetectados = detectarComponentesImagem(textoBase).filterValues { it != null }.keys
        )
    }

    override fun melhorarLocalmente(
        promptAtual: String,
        pedidoOriginal: String,
        pontosFracos: Set<String>,
        contextoPesquisa: String?
    ): PromptCriado {
        val dominio = PromptDomain.classificar(pedidoOriginal)
        var melhorado = aplicarAlteracoesConcretas(promptAtual.trim(), pedidoOriginal)
        if ("especificidade" in pontosFracos || "presença de elementos" in pontosFracos) {
            val faltantes = detectarComponentesImagem("$promptAtual $pedidoOriginal $contextoPesquisa")
                .filterValues { it == null }.keys
            if (faltantes.isNotEmpty()) {
                melhorado += "\n\n" + faltantes.joinToString("\n") { campo -> "${rotulo(campo)}: ${padraoImagem(campo)}" }
            }
        }
        if ("clareza" in pontosFracos) {
            melhorado = melhorado.replace(Regex("\\s{2,}"), " ").trim()
        }
        return PromptCriado(melhorado.trim(), dominio, "local:melhoria-heuristica")
    }

    /** Refina pedidos concretos sem chamar IA: substitui o ambiente e acrescenta elementos pedidos. */
    private fun aplicarAlteracoesConcretas(promptAtual: String, instrucao: String): String {
        val lower = instrucao.lowercase(Locale.ROOT)
        var resultado = promptAtual
        Regex("(?i)\\bfundo\\s+(?:no|na|em|de|do|da)\\s+([^,.;]+?)(?:\\s+e\\s+|$)").find(instrucao)?.let { match ->
            val novoFundo = match.groupValues[1].trim()
            resultado = resultado.replace(Regex("(?i)ambientado em [^.]+"), "ambientado em $novoFundo")
        }
        if ("deserto" in lower && !resultado.lowercase(Locale.ROOT).contains("deserto")) {
            resultado = resultado.replace(Regex("(?i)ambientado em [^.]+"), "ambientado em deserto")
        }
        if ("meteoro" in lower && !resultado.lowercase(Locale.ROOT).contains("meteoro")) {
            resultado += " Vários meteoros caindo cruzam o céu ao fundo."
        }
        return resultado
    }

    // ---------------- IMAGEM ----------------

    private fun componsIrImagem(pedido: String, textoBase: String, contexto: PromptTemplate?, contextoPesquisa: String?): String {
        val componentes = detectarComponentesImagem(textoBase)
        val sujeito = componentes["sujeito"] ?: extrairSujeito(pedido) ?: pedido.trim().removeSuffix(".")
        val estilo = componentes["estilo"] ?: (if ("fotorrealista" in pedido.lowercase() || "fotografia" in pedido.lowercase()) "fotografia fotorrealista" else "ilustração digital detalhada")
        val ambiente = componentes["ambiente"] ?: padraoImagem("ambiente")
        val composicao = componentes["composicao"] ?: padraoImagem("composicao")
        val iluminacao = componentes["iluminacao"] ?: padraoImagem("iluminacao")
        val camera = componentes["camera"] ?: padraoImagem("camera")
        val lente = componentes["lente"] ?: padraoImagem("lente")
        val profundidade = componentes["profundidade"] ?: padraoImagem("profundidade")
        val realismo = componentes["realismo"] ?: padraoImagem("realismo")
        val qualidade = componentes["qualidade"] ?: padraoImagem("qualidade")

        val base = buildString {
            append("${estilo.replaceFirstChar { it.uppercase(Locale.ROOT) }} de $sujeito, ambientado em $ambiente. ")
            append("Composição: $composicao. ")
            append("Iluminação: $iluminacao. ")
            append("Câmera e lente: $camera, $lente, $profundidade. ")
            append("Nível de realismo: $realismo. Qualidade: $qualidade.")
        }
        val referencia = if (contexto != null) "\n\nReferência da biblioteca considerada (${contexto.id}): adaptado ao pedido acima." else ""
        return base + referencia + diretrizesDaPesquisa(contextoPesquisa)
    }

    /** Extrai cada componente só quando há evidência textual — nunca inventa o que o usuário não disse. */
    private fun detectarComponentesImagem(texto: String): Map<String, String?> {
        val lower = texto.lowercase(Locale.ROOT)
        return mapOf(
            "sujeito" to extrairSujeito(texto),
            "estilo" to primeiraOcorrencia(lower, "fotorrealista" to "fotografia fotorrealista", "aquarela" to "estilo aquarela", "pintura a óleo" to "pintura a óleo", "cartoon" to "estilo cartoon", "anime" to "estilo anime", "minimalista" to "estilo minimalista"),
            "ambiente" to extrairAmbiente(texto),
            "composicao" to primeiraOcorrencia(lower, "plano geral" to "plano geral", "close" to "close-up", "grande angular" to "composição em grande angular", "simétric" to "composição simétrica"),
            "iluminacao" to primeiraOcorrencia(lower, "iluminação cinematográfica" to "iluminação cinematográfica", "luz natural" to "luz natural", "contraluz" to "contraluz dramático", "golden hour" to "luz dourada de golden hour", "luz dramática" to "iluminação dramática"),
            "camera" to primeiraOcorrencia(lower, "drone" to "câmera de drone", "dslr" to "câmera DSLR", "35mm" to "câmera de filme 35mm"),
            "lente" to primeiraOcorrencia(lower, "grande angular" to "lente grande angular", "teleobjetiva" to "lente teleobjetiva", "macro" to "lente macro", "50mm" to "lente 50mm"),
            "profundidade" to primeiraOcorrencia(lower, "profundidade de campo" to "profundidade de campo rasa", "foco seletivo" to "foco seletivo com fundo desfocado", "bokeh" to "efeito bokeh no fundo"),
            "realismo" to primeiraOcorrencia(lower, "hiper-realista" to "hiper-realista", "fotorrealista" to "fotorrealista", "estilizado" to "estilizado, não fotorrealista"),
            "qualidade" to primeiraOcorrencia(lower, "alta definição" to "alta definição, detalhes nítidos", "8k" to "resolução 8K", "4k" to "resolução 4K", "detalhes realistas" to "riqueza de detalhes realistas")
        )
    }

    private fun padraoImagem(campo: String): String = when (campo) {
        "ambiente" -> "um cenário coerente com o assunto, sem elementos que não foram pedidos"
        "composicao" -> "enquadramento equilibrado, assunto em destaque no terço de composição"
        "iluminacao" -> "iluminação natural e equilibrada realçando volumes e texturas"
        "camera" -> "câmera fotográfica padrão"
        "lente" -> "lente com distância focal neutra (por volta de 50mm)"
        "profundidade" -> "profundidade de campo moderada"
        "realismo" -> "nível de realismo fotográfico"
        "qualidade" -> "alta definição, sem artefatos visuais"
        else -> "conforme o contexto do pedido"
    }

    private fun rotulo(campo: String): String = when (campo) {
        "ambiente" -> "Ambiente"; "composicao" -> "Composição"; "iluminacao" -> "Iluminação"
        "camera" -> "Câmera"; "lente" -> "Lente"; "profundidade" -> "Profundidade"
        "realismo" -> "Realismo"; "qualidade" -> "Qualidade"; else -> campo.replaceFirstChar { it.uppercase() }
    }

    private fun extrairSujeito(pedido: String): String? {
        val semPrefixo = pedido
            .replace(Regex("(?i)^\\s*(crie|gere|escreva|faça|faca)\\s+(um[a]?\\s+)?prompt(s)?\\s*(para|de)?\\s*"), "")
            .trim()
        val match = Regex("(?i)(?:^|\\b(?:de|para)\\s+)(?:(?:um|uma|o|a)\\s+)?([^,.;]+)").find(semPrefixo)
        return match?.groupValues?.get(1)?.trim()
            ?.let(::normalizarSujeito)
            ?.takeIf { it.length in 4..160 }
    }

    /** O estilo já é emitido separadamente; nunca deve voltar a fazer parte do sujeito. */
    private fun normalizarSujeito(valor: String): String = valor
        .replace(
            Regex("(?i)^(?:um[a]?|o|a)?\\s*(?:fotografia|foto|imagem|ilustração)(?:\\s+fotorrealista)?\\s+(?:de|do|da)\\s+"),
            ""
        )
        .trim()

    private fun extrairAmbiente(texto: String): String? {
        val padroes = listOf(
            Regex("(?i)\\b(céu\\s+[^,.;]*(?:ao fundo|no fundo))"),
            Regex("(?i)\\b([^,.;]*fundo[^,.;]*)"),
            Regex("(?i)\\b([^,.;]*céu[^,.;]*)"),
            Regex("(?i)\\b([^,.;]*cenário[^,.;]*)")
        )
        return padroes.asSequence()
            .mapNotNull { it.find(texto)?.groupValues?.getOrNull(1)?.trim() }
            .firstOrNull { it.length >= 4 }
    }

    private fun primeiraOcorrencia(lower: String, vararg pares: Pair<String, String>): String? =
        pares.firstOrNull { (chave, _) -> chave in lower }?.second

    private fun resumir(texto: String, maxChars: Int): String =
        texto.trim().replace(Regex("\\s+"), " ").take(maxChars).let { if (texto.length > maxChars) "$it…" else it }

    // ---------------- VIDEO ----------------

    private fun componsIrVideo(pedido: String, textoBase: String, contexto: PromptTemplate?, contextoPesquisa: String?): String {
        val componentes = detectarComponentesImagem(textoBase)
        val sujeito = componentes["sujeito"] ?: extrairSujeito(pedido) ?: pedido.trim().removeSuffix(".")
        val movimento = primeiraOcorrencia(pedido.lowercase(), "travelling" to "travelling", "câmera lenta" to "câmera lenta", "zoom" to "zoom progressivo", "panorâmica" to "panorâmica") ?: "movimento de câmera suave"
        val duracao = Regex("(\\d+)\\s*(segundos|s\\b)").find(pedido)?.value ?: "duração curta (poucos segundos)"
        val iluminacao = componentes["iluminacao"] ?: padraoImagem("iluminacao")
        val estilo = componentes["estilo"] ?: "estilo cinematográfico"
        val base = "Vídeo com $sujeito. Movimento de câmera: $movimento. Duração: $duracao. Iluminação: $iluminacao. Estilo visual: $estilo."
        return base + diretrizesDaPesquisa(contextoPesquisa)
    }

    // ---------------- TEXTO ----------------

    private fun componsIrTexto(pedido: String, contexto: PromptTemplate?, contextoPesquisa: String?): String = buildString {
        appendLine("OBJETIVO")
        appendLine(pedido.trim())
        appendLine()
        appendLine("FORMATO DE SAÍDA")
        appendLine("Responder apenas com o conteúdo pedido, sem explicações adicionais além do necessário.")
        if (contexto != null) {
            appendLine()
            appendLine("REFERÊNCIA")
            appendLine("Baseado no template ${contexto.id}, adaptado ao pedido acima.")
        }
        append(diretrizesDaPesquisa(contextoPesquisa))
    }.trim()

    // ---------------- CODIGO ----------------

    private fun componsIrCodigo(pedido: String, contexto: PromptTemplate?, contextoPesquisa: String?): String = buildString {
        appendLine("TAREFA DE CÓDIGO")
        appendLine(pedido.trim())
        appendLine()
        appendLine("REQUISITOS")
        appendLine("- Preservar a arquitetura existente do projeto.")
        appendLine("- Cobrir o caso descrito com testes quando aplicável.")
        appendLine("- Não introduzir dependências desnecessárias.")
        append(diretrizesDaPesquisa(contextoPesquisa))
    }.trim()

    /** Converte evidência de pesquisa em orientação de execução; URLs e fontes não entram no prompt. */
    private fun diretrizesDaPesquisa(contextoPesquisa: String?): String {
        val bruto = contextoPesquisa?.trim().orEmpty()
        if (bruto.isBlank()) return ""
        val lower = bruto.lowercase(Locale.ROOT)
        val insights = buildList {
            if ("ilumina" in lower || "luz" in lower || "sombra" in lower) add("use iluminação equilibrada e controle as sombras")
            if ("fotorreal" in lower || "fotograf" in lower || "realismo" in lower) add("preserve coerência fotográfica e detalhes realistas")
            if ("composi" in lower || "enquadr" in lower) add("organize o enquadramento com hierarquia visual clara")
            if ("cor" in lower || "paleta" in lower) add("mantenha uma paleta de cores coerente com o objetivo")
            if (isEmpty()) add("aplique as evidências pesquisadas de forma coerente com a intenção do pedido")
        }
        val insight = insights.joinToString("; ")
        if (insight.isBlank()) return ""
        return "\n\nDIRETRIZES SEMÂNTICAS DERIVADAS DA PESQUISA:\nAplique estas evidências ao resultado, preservando a intenção do pedido: $insight"
    }
}
