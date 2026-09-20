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
        val dominio = PromptDomain.classificar(pedido)
        // Para IMAGEM/VIDEO, removemos primeiro o "invólucro" da própria solicitação
        // ("vamos criar um prompt para uma imagem de ...") — sem isso, quando o pedido
        // não começa com um verbo reconhecido (ex.: "vamos criar" em vez de "crie"), o
        // pedido inteiro (incluindo o pedido de meta-prompt) acaba virando o sujeito E
        // o ambiente da imagem, duplicando o texto do usuário no prompt final.
        val textoBase = when (dominio) {
            PromptDomain.IMAGEM, PromptDomain.VIDEO -> limparMetaPrompt(pedido)
            else -> pedido
        }
        val texto = when (dominio) {
            PromptDomain.IMAGEM -> componsIrImagem(pedido, textoBase, contexto, contextoPesquisa)
            PromptDomain.VIDEO -> componsIrVideo(pedido, textoBase, contexto, contextoPesquisa)
            PromptDomain.CODIGO -> componsIrCodigo(pedido, contexto, contextoPesquisa)
            PromptDomain.TEXTO -> componsIrTexto(pedido, contexto, contextoPesquisa)
        }
        val evidence = evidenciasDaPesquisa(contextoPesquisa)
        return PromptCriado(
            texto = texto,
            dominio = dominio,
            origem = if (contexto != null) "local:adaptado-de:${contexto.id}" else "local:criado-do-zero",
            componentesDetectados = detectarComponentesImagem(textoBase).filterValues { it != null }.keys,
            reasoning = tracePara(pedido, textoBase, evidence)
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
        melhorado = refinarComPesquisa(melhorado, pedidoOriginal.lowercase(Locale.ROOT), contextoPesquisa)
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
        val evidence = evidenciasDaPesquisa(contextoPesquisa)
        return PromptCriado(
            melhorado.trim(), dominio, "local:melhoria-heuristica",
            reasoning = tracePara(pedidoOriginal, pedidoOriginal, evidence).copy(
                revisions = pontosFracos.toList().sorted()
            )
        )
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
        resultado = aplicarEstiloFotorrealista(resultado, lower)
        resultado = aplicarHorarioDoDia(resultado, lower)
        resultado = aplicarPontoDeVista(resultado, instrucao)
        return resultado
    }

    /**
     * Usa a pesquisa web só para refinar o que o usuário JÁ pediu, com termo técnico que a própria fonte
     * trouxe (nunca inventa fatos): luz de golden hour para pôr do sol/entardecer. Sem pesquisa, ou sem esse
     * termo nas fontes, o prompt não muda.
     */
    private fun refinarComPesquisa(prompt: String, lowerInstrucao: String, contextoPesquisa: String?): String {
        val pesquisa = contextoPesquisa.orEmpty().lowercase(Locale.ROOT)
        if (pesquisa.isBlank()) return prompt
        var resultado = prompt
        val pediuLuzDourada = Regex("p[oô]r do sol|entardecer|crep[uú]sculo").containsMatchIn(lowerInstrucao)
        if (pediuLuzDourada && ("golden hour" in pesquisa || "hora dourada" in pesquisa) && "golden hour" !in resultado.lowercase(Locale.ROOT)) {
            resultado = resultado.replace("luz dourada do ", "luz dourada de golden hour, no ")
        }
        return resultado
    }

    /**
     * "ao pôr do sol" / "entardecer" / "crepúsculo": vira luz no campo Iluminação e entra no ambiente.
     * O termo é escrito com a grafia correta ("por do sol" -> "pôr do sol"); a crítica ignora acentos.
     */
    private fun aplicarHorarioDoDia(prompt: String, lowerInstrucao: String): String {
        val termo = Regex("p[oô]r do sol|entardecer|crep[uú]sculo").find(lowerInstrucao)?.value ?: return prompt
        val nome = if (termo.startsWith("p")) "pôr do sol" else termo
        if (nome in prompt.lowercase(Locale.ROOT)) return prompt
        val luz = "luz dourada do $nome, sombras longas e céu alaranjado"
        var resultado = Regex("(?i)(Iluminação:\\s*)[^.]+").replace(prompt) { it.groupValues[1] + luz }
        if (resultado == prompt) resultado = "${prompt.trimEnd()} Iluminação: $luz."
        return Regex("(?i)(ambientado em [^.]+)").replace(resultado) { "${it.groupValues[1]} ao $nome" }
    }

    /**
     * "fotorrealista": troca o estilo de abertura ("Ilustração digital detalhada de ...") e o campo de
     * realismo. Sem isso, um follow-up "muda para fotorrealista" deixava o prompt como ilustração.
     */
    private fun aplicarEstiloFotorrealista(prompt: String, lowerInstrucao: String): String {
        val pediu = Regex("fotorreal|fotogr[aá]fic|hiper-?realis").containsMatchIn(lowerInstrucao)
        val negou = Regex("(?:não|nao|sem)\\s+(?:seja\\s+|ser\\s+)?(?:fotorreal|fotogr)").containsMatchIn(lowerInstrucao)
        if (!pediu || negou || "fotorrealista" in prompt.lowercase(Locale.ROOT)) return prompt
        var resultado = Regex("(?i)^\\s*(ilustra[çc][ãa]o digital detalhada|ilustra[çc][ãa]o digital|ilustra[çc][ãa]o|pintura digital|desenho digital|arte digital)\\b")
            .replace(prompt, "Fotografia fotorrealista")
        resultado = Regex("(?i)(N[ií]vel de realismo:\\s*)[^.]+")
            .replace(resultado) { it.groupValues[1] + "fotorrealista, com texturas e iluminação de fotografia real" }
        if ("fotorrealista" !in resultado.lowercase(Locale.ROOT)) resultado = "$resultado Estilo: fotografia fotorrealista."
        // Prompt de ilustração não tem linha de câmera; ao virar fotografia ele precisa de uma.
        if (!resultado.contains("Câmera e lente:", ignoreCase = true) && resultado.contains("Nível de realismo:")) {
            val camera = "Câmera e lente: ${padraoImagem("camera")}, ${padraoImagem("lente")}, ${padraoImagem("profundidade")}. "
            resultado = resultado.replaceFirst("Nível de realismo:", camera + "Nível de realismo:")
        }
        return resultado
    }

    /**
     * "visão de uma plataforma de longe" / "visto de longe" / "plataforma ao longe": vira composição
     * explícita, preservando a frase do usuário (é ela que a crítica procura no resultado).
     */
    private fun aplicarPontoDeVista(prompt: String, instrucao: String): String {
        val comObjeto = Regex("(?i)\\b((?:vis[ãa]o|vista|visto|enquadrad[oa]|imagem|foto)\\s+(?:de|d[aeo]s?)\\s+(?:um[a]?\\s+|o\\s+|a\\s+)?[\\p{L}\\d\\- ]{2,60}?\\s+(?:de longe|ao longe|à distância|a distância|distante))")
        val semObjeto = Regex("(?i)\\b((?:vis[ãa]o|vista|visto)\\s+(?:de longe|ao longe|à distância|a distância|distante))")
        val frase = (comObjeto.find(instrucao) ?: semObjeto.find(instrucao))?.groupValues?.get(1)?.trim() ?: return prompt
        if (frase.lowercase(Locale.ROOT) in prompt.lowercase(Locale.ROOT)) return prompt
        val novaComposicao = "plano geral aberto, $frase, com o assunto principal ainda em destaque"
        val comComposicao = Regex("(?i)(Composição:\\s*)[^.]+").replace(prompt) { it.groupValues[1] + novaComposicao }
        val resultado = if (comComposicao != prompt) comComposicao else "${prompt.trimEnd()} Composição: $novaComposicao."
        // Visão de longe com lente "neutra de 50mm" é contraditória: objeto distante pede lente longa.
        return Regex("(?i)lente com distância focal neutra(?:\\s*\\([^)]*\\))?")
            .replace(resultado) { "lente teleobjetiva, compressão de planos" }
    }

    // ---------------- IMAGEM ----------------

    private fun componsIrImagem(pedido: String, textoBase: String, contexto: PromptTemplate?, contextoPesquisa: String?): String {
        val componentes = detectarComponentesImagem(textoBase)
        val (sujeitoSplit, ambienteSplit) = extrairSujeitoEAmbiente(textoBase)
        // Nunca use a instrução inteira como sujeito: pedidos de melhoria/metaprompt
        // acabariam aparecendo literalmente dentro da imagem. O executor faz o
        // checklist e pergunta pelo sujeito quando ele não estiver identificável.
        val sujeito = sujeitoSplit ?: componentes["sujeito"] ?: extrairSujeito(textoBase) ?: "o sujeito principal especificado pelo usuário"
        val estilo = componentes["estilo"] ?: (if ("fotorrealista" in pedido.lowercase() || "fotografia" in pedido.lowercase()) "fotografia fotorrealista" else "ilustração digital detalhada")
        val pesquisa = insightsDaPesquisa(contextoPesquisa)
        val ambiente = ambienteSplit ?: componentes["ambiente"] ?: pesquisa["ambiente"] ?: padraoImagem("ambiente")
        val composicao = componentes["composicao"] ?: pesquisa["composicao"] ?: padraoImagem("composicao")
        val iluminacao = componentes["iluminacao"] ?: pesquisa["iluminacao"] ?: padraoImagem("iluminacao")
        // Coerência estilo x técnica: câmera/lente/realismo fotográfico só entram em prompt de fotografia
        // (ou quando o usuário pediu câmera/lente/profundidade). Ilustração não leva "câmera fotográfica padrão".
        val fotografico = Regex("fotograf|fotorreal").containsMatchIn(estilo.lowercase(Locale.ROOT))
        val cameraExplicita = listOf("camera", "lente", "profundidade").any { componentes[it] != null }
        val camera = componentes["camera"] ?: padraoImagem("camera")
        val lente = componentes["lente"] ?: padraoImagem("lente")
        val profundidade = componentes["profundidade"] ?: padraoImagem("profundidade")
        val realismo = componentes["realismo"]
            ?: if (fotografico) padraoImagem("realismo") else "acabamento de ilustração digital, cores ricas e volumes bem definidos"
        val qualidade = componentes["qualidade"] ?: padraoImagem("qualidade")

        val base = buildString {
            append("${estilo.replaceFirstChar { it.uppercase(Locale.ROOT) }} de $sujeito, ambientado em $ambiente. ")
            append("Composição: $composicao. ")
            append("Iluminação: $iluminacao. ")
            if (fotografico || cameraExplicita) append("Câmera e lente: $camera, $lente, $profundidade. ")
            append("Nível de realismo: $realismo. Qualidade: $qualidade.")
        }
        val referencia = if (contexto != null) "\n\nReferência da biblioteca considerada (${contexto.id}): adaptado ao pedido acima." else ""
        return base + referencia
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
        "realismo" -> "fotográfico, com texturas e proporções naturais"
        "qualidade" -> "alta definição, sem artefatos visuais"
        else -> "conforme o contexto do pedido"
    }

    private fun rotulo(campo: String): String = when (campo) {
        "ambiente" -> "Ambiente"; "composicao" -> "Composição"; "iluminacao" -> "Iluminação"
        "camera" -> "Câmera"; "lente" -> "Lente"; "profundidade" -> "Profundidade"
        "realismo" -> "Realismo"; "qualidade" -> "Qualidade"; else -> campo.replaceFirstChar { it.uppercase() }
    }

    /**
     * Remove o "invólucro" de meta-pedido ("vamos criar um prompt para uma imagem realista de...")
     * cobrindo tanto pedidos formais ("crie um prompt de...") quanto informais/conversacionais
     * ("vamos criar", "eu quero", "preciso de", "gostaria de"...), e em seguida remove também o
     * substantivo do meio ("uma imagem realista de...") para chegar direto ao assunto pedido.
     * Sem isso, um pedido que não comece com um verbo imperativo reconhecido faz o texto inteiro
     * (incluindo o próprio pedido de meta-prompt) virar sujeito/ambiente da imagem, duplicando
     * a instrução do usuário dentro do prompt gerado.
     */
    private fun limparMetaPrompt(texto: String): String {
        val semMeta = texto.replace(
            Regex(
                "(?i)^\\s*(?:vamos\\s+|eu\\s+quero\\s+|quero\\s+|preciso\\s+(?:de\\s+)?|gostaria\\s+de\\s+|" +
                    "me\\s+ajude\\s+a\\s+|ajude-me\\s+a\\s+|podia\\s+|pode\\s+)?" +
                    "(?:criar|crie|gerar|gere|escrever|escreva|fazer|fa[çc]a|montar|monte|desenvolver|desenvolva|elaborar|elabore)\\s+" +
                    "(?:um[a]?\\s+)?prompt(?:s)?\\s*(?:para|de)?\\s*"
            ),
            ""
        ).trim()
        return semMeta.replace(
            Regex("(?i)^(?:um[a]?\\s+)?(?:fotografia|foto|imagem|ilustra[çc][ãa]o)(?:\\s+(?:realista|fotorrealista))?\\s+(?:de|do|da)\\s+"),
            ""
        ).trim()
    }

    /**
     * Tenta separar num único passo o que é "sujeito/ação" (o que acontece em primeiro plano)
     * do que é "ambiente/cenário" (onde acontece), usando o primeiro " em um(a)/o/a ..." do texto
     * já limpo como divisor. Evita que o mesmo trecho longo vire sujeito E ambiente ao mesmo tempo
     * (o sintoma relatado: o prompt final repetindo a frase inteira do usuário duas vezes).
     * Se não houver esse divisor, cada componente cai de volta nos extratores individuais.
     */
    private fun extrairSujeitoEAmbiente(textoLimpo: String): Pair<String?, String?> {
        val divisor = Regex("(?i)^(.+?)\\s+em\\s+(um[a]?\\s+.+|o\\s+.+|a\\s+.+)$").find(textoLimpo)
        if (divisor != null) {
            val sujeito = normalizarSujeito(divisor.groupValues[1].trim()).takeIf { it.length in 4..160 }
            val ambiente = divisor.groupValues[2].trim().takeIf { it.length in 4..200 }
            if (sujeito != null) return sujeito to ambiente
        }
        return extrairSujeito(textoLimpo) to extrairAmbiente(textoLimpo)
    }

    private fun extrairSujeito(pedido: String): String? {
        val semPrefixo = limparMetaPrompt(pedido)
        val match = Regex("(?i)(?:^|\\b(?:de|para)\\s+)((?:(?:um|uma|o|a)\\s+)?[^,.;]+)").find(semPrefixo)
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

    private fun insightsDaPesquisa(contexto: String?): Map<String, String> {
        val lower = contexto.orEmpty().lowercase(Locale.ROOT)
        return buildMap {
            if ("golden hour" in lower || "luz dourada" in lower) put("iluminacao", "luz dourada de golden hour")
            else if ("luz natural" in lower || "natural light" in lower) put("iluminacao", "luz natural equilibrada")
            if ("plano geral" in lower || "wide shot" in lower) put("composicao", "plano geral com hierarquia visual")
            if ("céu" in lower || "paisagem" in lower || "landscape" in lower) put("ambiente", "paisagem coerente com céu ao fundo")
        }
    }

    private fun resumir(texto: String, maxChars: Int): String =
        texto.trim().replace(Regex("\\s+"), " ").take(maxChars).let { if (texto.length > maxChars) "$it…" else it }

    // ---------------- VIDEO ----------------

    private fun componsIrVideo(pedido: String, textoBase: String, contexto: PromptTemplate?, contextoPesquisa: String?): String {
        val componentes = detectarComponentesImagem(textoBase)
        val sujeito = extrairSujeitoEAmbiente(textoBase).first ?: componentes["sujeito"] ?: extrairSujeito(textoBase) ?: "o sujeito principal especificado pelo usuário"
        val movimento = primeiraOcorrencia(pedido.lowercase(), "travelling" to "travelling", "câmera lenta" to "câmera lenta", "zoom" to "zoom progressivo", "panorâmica" to "panorâmica") ?: "movimento de câmera suave"
        val duracao = Regex("(\\d+)\\s*(segundos|s\\b)").find(pedido)?.value ?: "duração curta (poucos segundos)"
        val iluminacao = componentes["iluminacao"] ?: insightsDaPesquisa(contextoPesquisa)["iluminacao"] ?: padraoImagem("iluminacao")
        val estilo = componentes["estilo"] ?: "estilo cinematográfico"
        val base = "Vídeo com $sujeito. Movimento de câmera: $movimento. Duração: $duracao. Iluminação: $iluminacao. Estilo visual: $estilo."
        return base
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
    }.trim()

    /** Extrai evidências reais para o trace; nunca cria um fallback genérico nem polui o prompt final. */
    private fun evidenciasDaPesquisa(contextoPesquisa: String?): List<String> {
        val bruto = contextoPesquisa?.trim().orEmpty()
        if (bruto.isBlank()) return emptyList()
        val lower = bruto.lowercase(Locale.ROOT)
        return buildList {
            if ("ilumina" in lower || "luz" in lower || "sombra" in lower) add("iluminação, luz e controle de sombras")
            if ("fotorreal" in lower || "fotograf" in lower || "realismo" in lower) add("coerência fotográfica e detalhes realistas")
            if ("composi" in lower || "enquadr" in lower) add("enquadramento e hierarquia visual")
            if (Regex("\\b(cor|cores|paleta|cromática)\\b").containsMatchIn(lower)) add("paleta e coerência de cores")
            Regex("(?i)\\b(?:contraste|textura|profundidade de campo|bokeh|perspectiva|temperatura de cor)\\b[^.;,]{0,80}")
                .findAll(bruto).map { it.value.trim() }.forEach { add(it) }
        }.distinct()
    }

    private fun tracePara(pedido: String, textoBase: String, evidence: List<String>): PromptReasoningTrace {
        val componentes = detectarComponentesImagem(textoBase).filterValues { it != null }.map { "${it.key}: ${it.value}" }
        return PromptReasoningTrace(
            intent = PromptDomain.classificar(pedido).name.lowercase(Locale.ROOT),
            mandatoryElements = componentes,
            evidence = evidence,
            assumptions = if (componentes.isEmpty()) listOf("componentes visuais ausentes foram preenchidos por padrões determinísticos") else emptyList()
        )
    }
}
