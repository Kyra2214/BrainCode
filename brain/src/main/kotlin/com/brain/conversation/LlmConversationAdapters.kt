package com.brain.conversation

import com.brain.provider.ProviderClient
import com.brain.provider.ProviderRequest
import com.brain.router.PapelPipeline
import com.brain.secretary.Door
import com.brain.secretary.OrderIntent
import org.json.JSONObject
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Resultado mínimo e auditável de uma chamada ao gateway de IA. */
data class BrainCompletion(
    val text: String,
    val modelId: String,
    val costTier: String,
    val providerId: String = "unknown"
)

/** Gateway abstrato; a implementação de produção pode ser ligada ao BrainApiGateway do app. */
fun interface BrainApiGateway {
    fun complete(prompt: String, pipeline: PapelPipeline, authorizedAccountIds: Set<String>): BrainCompletion
}

class ProviderBrainApiGateway(
    private val client: ProviderClient,
    private val modelId: String,
    private val costTier: String = "standard",
    private val providerId: String = "provider",
    private val timeoutMs: Long = 15_000L
) : BrainApiGateway {
    override fun complete(prompt: String, pipeline: PapelPipeline, authorizedAccountIds: Set<String>): BrainCompletion {
        require(prompt.isNotBlank()) { "prompt não pode ser vazio" }
        val accountId = authorizedAccountIds.firstOrNull()
        val executor = Executors.newSingleThreadExecutor()
        return try {
            val response = executor.submit(Callable {
                client.complete(ProviderRequest(modelId, prompt, accountId = accountId)).getOrThrow()
            }).get(timeoutMs, TimeUnit.MILLISECONDS)
            check(response.statusCode in 200..299) { "gateway retornou HTTP ${response.statusCode}" }
            BrainCompletion(response.body, modelId, costTier, response.providerId.ifBlank { providerId })
        } finally {
            executor.shutdownNow()
        }
    }
}

/** Parser estrito: a LLM pode retornar somente JSON de contrato, sem texto livre. */
object StructuredLlmParser {
    fun objectFrom(text: String): JSONObject {
        val candidate = text.trim().let { value ->
            if (value.startsWith("```") && value.endsWith("```") )
                value.substringAfter('\n').substringBeforeLast("```").trim()
            else value
        }
        return runCatching { JSONObject(candidate) }.getOrElse {
            throw IllegalArgumentException("resposta estruturada inválida: JSON esperado")
        }
    }

    fun requiredString(json: JSONObject, key: String): String = json.optString(key).trim().also {
        require(it.isNotBlank()) { "campo obrigatório ausente: $key" }
    }

    fun stringMap(json: JSONObject, key: String): Map<String, String> {
        val value = json.optJSONObject(key) ?: return emptyMap()
        return value.keys().asSequence().associateWith { value.optString(it).trim() }.filterValues { it.isNotBlank() }
    }
}

class LlmIntentAdvisor(
    private val gateway: BrainApiGateway,
    private val fallback: IntentAdvisor = NoOpIntentAdvisor,
    private val accounts: Set<String> = emptySet()
) : IntentAdvisor {
    override fun revisarClassificacao(prompt: String, classificacaoTentativa: OrderIntent): OrderIntentSugerido =
        runCatching {
            val completion = gateway.complete(intentPrompt(prompt, classificacaoTentativa), PapelPipeline.CONVERSACAO, accounts)
            val json = StructuredLlmParser.objectFrom(completion.text)
            OrderIntentSugerido(
                door = Door.valueOf(StructuredLlmParser.requiredString(json, "door").uppercase()),
                intent = json.optString("intent").takeIf { it.isNotBlank() },
                confidence = json.optDouble("confidence").takeIf { !it.isNaN() },
                rationale = json.optString("rationale").takeIf { it.isNotBlank() }
            )
        }.getOrElse { fallback.revisarClassificacao(prompt, classificacaoTentativa) }

    private fun intentPrompt(prompt: String, tentative: OrderIntent) = """
        Classifique a porta do pedido. Responda somente JSON com door (CHAT|PROMPT|CREATE), intent, confidence e rationale.
        Classificação determinística: ${tentative.door}. Pedido: $prompt
    """.trimIndent()
}

class LlmConversationInterpreter(
    private val gateway: BrainApiGateway,
    private val fallback: ConversationInterpreter = NoOpConversationInterpreter,
    private val accounts: Set<String> = emptySet()
) : ConversationInterpreter {
    override fun extrairEstrutura(prompt: String, context: ConversationContext): EstruturaExtraida =
        runCatching {
            val completion = gateway.complete(interpreterPrompt(prompt, context), PapelPipeline.CONVERSACAO, accounts)
            val json = StructuredLlmParser.objectFrom(completion.text)
            EstruturaExtraida(
                intent = StructuredLlmParser.requiredString(json, "intent"),
                normalizedQuery = StructuredLlmParser.requiredString(json, "normalizedQuery"),
                entities = StructuredLlmParser.stringMap(json, "entities"),
                parametrosParaAgente = StructuredLlmParser.stringMap(json, "parameters")
            )
        }.getOrElse { fallback.extrairEstrutura(prompt, context) }

    private fun interpreterPrompt(prompt: String, context: ConversationContext) = """
        Extraia a estrutura do pedido para recuperação de conhecimento. Responda somente JSON com intent, normalizedQuery,
        entities (objeto string-string) e parameters (objeto string-string). Idioma pt-BR. Pedido: $prompt
        requestId: ${context.requestId}
    """.trimIndent()
}

class LlmOutputReviewer(
    private val gateway: BrainApiGateway,
    private val fallback: OutputReviewer = NoOpOutputReviewer,
    private val accounts: Set<String> = emptySet()
) : OutputReviewer {
    override fun conferir(promptOriginal: String, saidaDoAgente: String): RevisaoAchados =
        runCatching {
            val completion = gateway.complete(reviewerPrompt(promptOriginal, saidaDoAgente), PapelPipeline.CONVERSACAO, accounts)
            val json = StructuredLlmParser.objectFrom(completion.text)
            RevisaoAchados(
                respondeAoPedido = json.optBoolean("respondeAoPedido"),
                completo = json.optBoolean("completo"),
                observacoes = buildList { if (json.has("observacoes")) json.getJSONArray("observacoes").forEach { add(it.toString()) } }
            )
        }.getOrElse { fallback.conferir(promptOriginal, saidaDoAgente) }

    private fun reviewerPrompt(prompt: String, output: String) = """
        Revise a resposta abaixo. Responda somente JSON com respondeAoPedido (boolean), completo (boolean) e observacoes (array).
        Pedido: $prompt
        Resposta: $output
    """.trimIndent()
}

/** Evidência padronizada para cada hop LLM, sem persistir prompt ou resposta. */
fun llmEvidence(hop: String, completion: BrainCompletion): List<String> = listOf(
    "chat:llm:$hop", "model=${completion.modelId}", "cost=${completion.costTier}", "provider=${completion.providerId}"
)

fun llmFailureEvidence(hop: String, reason: String): List<String> = listOf("chat:llm:$hop:fallback", "error=${reason.take(160)}")

private fun <T> org.json.JSONArray.forEach(action: (Any) -> Unit) { for (index in 0 until length()) action(get(index)) }
private fun Double.isNaN() = java.lang.Double.isNaN(this)

class ConversationKnowledgeFlow(
    private val memory: com.brain.memory.KnowledgeMemory,
    private val interpreter: ConversationInterpreter = NoOpConversationInterpreter,
    private val metrics: ConversationMetrics = ConversationMetrics(),
    private val evidence: (String) -> Unit = {}
) {
    data class Recall(val entry: com.brain.memory.KnowledgeEntry?, val structure: EstruturaExtraida?, val layer: String)

    fun recall(prompt: String, context: ConversationContext = ConversationContext()): Recall {
        memory.findValidated(prompt)?.let { hit ->
            metrics.recordCacheHit("layer1"); evidence("chat:cache:hit:layer1"); return Recall(hit, null, "layer1")
        }
        metrics.recordCacheMiss()
        val structure = interpreter.extrairEstrutura(prompt, context)
        val hit = memory.findValidatedStructured(prompt, structure.intent, structure.entities)
        if (hit != null) { metrics.recordCacheHit("layer1"); evidence("chat:cache:hit:layer1") }
        else evidence("chat:cache:miss:layer1")
        return Recall(hit, structure, if (hit == null) "miss" else "layer1-structured")
    }
}
