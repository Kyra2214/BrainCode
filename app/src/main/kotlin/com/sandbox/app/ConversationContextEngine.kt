package com.sandbox.app

data class ConversationContext(
    val idea: String? = null,
    val requirements: List<String> = emptyList(),
    val decisions: List<String> = emptyList(),
    val discarded: List<String> = emptyList(),
    val pending: List<String> = emptyList(),
    val artifacts: List<String> = emptyList(),
    val references: List<String> = emptyList()
)

data class ResolvedObjective(
    val currentPrompt: String,
    val context: ConversationContext,
    val objective: String
)

/** Lê a sessão inteira e envia ao Brain apenas fatos e artefatos compactados. */
class ConversationContextEngine {
    fun resolve(history: List<ChatMessage>, currentPrompt: String): ResolvedObjective {
        val prompt = currentPrompt.trim()
        require(prompt.isNotBlank()) { "prompt atual não pode ser vazio" }
        val prior = history.filterNot { it.role == ChatRole.USER && it.content.trim() == prompt }
        // Um novo pedido deve começar limpo. O histórico só participa quando o usuário
        // usa uma referência explícita de continuidade ("melhore ele", "implemente isso",
        // "agora mude...", etc.); caso contrário, requisitos/decisões de outro pedido não
        // podem contaminar a geração atual.
        val continuidade = REFERENCE_PATTERN.containsMatchIn(prompt.lowercase())
        val contextoAnterior = if (continuidade) prior else emptyList()
        val userText = contextoAnterior.filter { it.role == ChatRole.USER }.map { it.content.trim() }.filter(String::isNotBlank)
        val assistantEntries = contextoAnterior.withIndex().filter { (_, message) ->
            message.role == ChatRole.ASSISTANT && message.content.trim().isNotBlank()
        }
        val assistantMessages = assistantEntries.map { it.value }
        val assistantText = assistantMessages.map { it.content.trim() }
        val all = userText + assistantText
        val idea = all.lastOrNull { IDEA_MARKERS.any { marker -> it.lowercase().contains(marker) } }
        val discarded = compact(all.filter { DISCARDED_MARKERS.any { marker -> it.lowercase().contains(marker) } })
        val pending = compact(all.filter { PENDING_MARKERS.any { marker -> it.lowercase().contains(marker) } })
        val rawDecisions = all.filter { DECISION_MARKERS.any { marker -> it.lowercase().contains(marker) } }
        val decisions = latestDecisions(rawDecisions, discarded)
        val requirements = compact(all.filter { REQUIREMENT_MARKERS.any { marker -> it.lowercase().contains(marker) } }
            .filterNot { discarded.any { rejected -> overlaps(it, rejected) } })
        // O artefato é só o prompt/entrega — sem o invólucro "Encontrei um prompt ... (qualidade 81%):".
        val artifactEntries = assistantEntries.filter { (index, message) ->
            message.contentType != GeneratedContentType.TEXT ||
                PromptEnvelope.temInvolucro(message.content) ||
                isArtifact(message.content) ||
                isPromptOutput(contextoAnterior, index, message)
        }
        val artifacts = compact(artifactEntries.map { PromptEnvelope.extrairPrompt(it.value.content) }, 12000)
        val references = if (REFERENCE_PATTERN.containsMatchIn(prompt.lowercase()) || artifacts.lastOrNull()?.let { overlaps(prompt, it) } == true) {
            // O conteúdo limpo pode não conter a palavra "prompt"; use tipo, envelope
            // ou pedido do turno anterior para preservar o roteamento do follow-up.
            val artefatoEraPrompt = artifactEntries.lastOrNull()?.let { (index, message) ->
                isPromptOutput(contextoAnterior, index, message)
            } == true
            listOfNotNull(
                idea?.let { "ideia: $it" },
                if (artefatoEraPrompt) "tipo da referência: prompt" else null,
                artifacts.lastOrNull()?.let { "artefato anterior: $it" }
            )
        } else emptyList()
        val context = ConversationContext(idea, requirements, decisions, discarded, pending, artifacts, references)
        return ResolvedObjective(prompt, context, buildObjective(prompt, context))
    }

    private fun buildObjective(prompt: String, context: ConversationContext): String {
        if (context.idea == null && context.requirements.isEmpty() && context.decisions.isEmpty() && context.references.isEmpty()) return prompt
        return buildString {
            append("Objetivo atual: ").append(prompt)
            context.idea?.let { append("\nIdeia/projeto ativo: ").append(it) }
            if (context.requirements.isNotEmpty()) append("\nRequisitos relevantes:\n- ").append(context.requirements.joinToString("\n- "))
            if (context.decisions.isNotEmpty()) append("\nDecisões atuais:\n- ").append(context.decisions.joinToString("\n- "))
            if (context.discarded.isNotEmpty()) append("\nIdeias descartadas (não usar):\n- ").append(context.discarded.joinToString("\n- "))
            if (context.pending.isNotEmpty()) append("\nPendências:\n- ").append(context.pending.joinToString("\n- "))
            if (context.references.isNotEmpty()) append("\nReferências resolvidas:\n- ").append(context.references.joinToString("\n- "))
        }
    }

    private fun latestDecisions(candidates: List<String>, discarded: List<String>): List<String> = candidates
        .filterNot { decision -> discarded.any { rejected -> overlaps(decision, rejected) } }
        .fold(linkedMapOf<String, String>()) { result, decision ->
            val key = decision.lowercase().substringBefore(" usar ").substringBefore(" para ").trim().take(80)
            result[key] = decision
            result
        }.values.toList().let(::compact)

    private fun compact(items: List<String>, maxChars: Int = 6000): List<String> {
        var used = 0
        return items.asReversed().takeWhile { item ->
            if (used + item.length > maxChars) false else { used += item.length; true }
        }.asReversed()
    }

    private fun overlaps(a: String, b: String): Boolean {
        val aTokens = a.lowercase().split(Regex("[^\\p{L}\\p{N}]+" )).filter { it.length > 3 }.toSet()
        val bTokens = b.lowercase().split(Regex("[^\\p{L}\\p{N}]+" )).filter { it.length > 3 }.toSet()
        return aTokens.intersect(bTokens).size >= 2
    }

    private fun isArtifact(text: String): Boolean =
        listOf("```", "prompt:", "especificação:", "implementação:", "arquivo:", "download:", "anexo:").any {
            it in text.lowercase()
        }

    private fun isPromptOutput(history: List<ChatMessage>, index: Int, message: ChatMessage): Boolean {
        if (message.contentType == GeneratedContentType.PROMPT || PromptEnvelope.temInvolucro(message.content)) return true
        val previousUserRequest = history.take(index).lastOrNull { it.role == ChatRole.USER }?.content.orEmpty()
        return PROMPT_REQUEST_PATTERN.containsMatchIn(previousUserRequest)
    }

    companion object {
        private val REFERENCE_PATTERN = Regex(
            "(?i)\\b(?:isso|isso aí|ele|ela|aquele|aquela|continua|aquilo|melhore|melhorar|melhora|" +
                "otimize|otimizar|refaça|refaca|refazer|aprimore|aprimorar|reescreva|reescrever|" +
                "reformule|reformular|capriche|ajuste|ajustar)\\p{L}*\\b|" +
                "\\b(?:vamos|quero|preciso|pode|poderia)\\s+(?:mudar|alterar|modificar)\\s+(?:o\\s+)?(?:prompt|artefato|isso|ele|ela)\\b|" +
                "\\b(?:faça|faca|faz|fazer)\\s+(?:isso|ele|ela|algo\\s+)?melhor\\b|" +
                "vamos\\s+fazer|agora\\s+(?:implement|quero\\s+(?:o|a|um|uma)\\s+(?:fundo|ambiente|elemento|objeto))"
        )
        private val PROMPT_REQUEST_PATTERN = Regex(
            "(?is)\\b(?:crie|gere|elabore|escreva|produza|monte|faça|faca|desenvolva)\\b.{0,100}\\bprompt\\b"
        )
        private val IDEA_MARKERS = listOf("tenho uma ideia", "projeto", "aplicativo", "aplicação", "produto")
        private val REQUIREMENT_MARKERS = listOf("precisa", "deve", "requisito", "quero também", "não quero", "poderá", "offline")
        private val DECISION_MARKERS = listOf("vamos usar", "decidimos", "escolhemos", "mudamos", "usar ")
        private val DISCARDED_MARKERS = listOf("descartamos", "não vamos usar", "mudamos de ideia", "foi descartad")
        private val PENDING_MARKERS = listOf("pendência", "pendente", "falta decidir", "a definir", "precisa decidir")
    }
}

fun ResolvedObjective.toBrainObjective(): String = objective
