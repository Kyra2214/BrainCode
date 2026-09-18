package com.sandbox.app

import com.brain.memory.ConservativeKnowledgeCritic
import com.brain.memory.KnowledgeCritic
import com.brain.memory.KnowledgeCriticDecision
import com.brain.memory.KnowledgeEntry
import com.brain.memory.KnowledgeCitation
import com.brain.memory.KnowledgeLearningCycle
import com.brain.memory.KnowledgeSource
import com.brain.provider.ProviderClient
import com.brain.provider.ProviderDispatcher
import com.brain.provider.ProviderRequest
import com.brain.provider.ProviderResponse
import com.brain.provider.AccountAwareProviderClient
import com.brain.provider.CredentialProvider
import com.brain.account.Account
import com.brain.account.AccountHealth
import com.brain.account.AccountPool
import com.brain.account.AccountRouteDecision
import com.brain.account.AccountRouteRequest
import com.brain.account.AccountRouter
import com.brain.account.AccountFailureClass
import com.brain.account.CredentialRef
import com.brain.account.SelectionPolicy
import com.brain.router.ApiCatalogRegistry
import com.brain.router.DefaultAIRouter
import com.brain.router.DynamicFreeApiCatalog
import com.brain.router.PapelPipeline
import com.brain.router.ProviderModel
import com.brain.router.RoutingDecision
import com.brain.capability.CostClass
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Ponte interna Brain -> memória -> APIs gratuitas; o usuário não conversa com provider/modelo. */
class BrainApiGateway(
    private val providers: List<ApiProvider>,
    private val keyStore: ApiKeyStore,
    private val router: DefaultAIRouter = DefaultAIRouter(),
    private val learning: KnowledgeLearningCycle = KnowledgeLearningCycle(),
    private val critic: KnowledgeCritic = ConservativeKnowledgeCritic()
) {
    data class GatewayResult(
        val text: String,
        val providerId: String,
        val modelId: String,
        val attempts: List<String>,
        val source: KnowledgeSource? = null,
        val knowledgeId: String? = null,
        val fromMemory: Boolean = false,
        val knowledgeValidated: Boolean = false,
        /** Tier de custo real do provider/modelo que respondeu (FREE quando veio da memória). */
        val costClass: CostClass = CostClass.FREE
    )

    fun complete(prompt: String, papel: PapelPipeline = PapelPipeline.ESCRITA_DE_PROMPT): GatewayResult {
        require(prompt.isNotBlank()) { "prompt não pode ser vazio" }

        // 1. Memória: se o Brain já conhece a resposta validada, não consulta nada externo.
        learning.recall(prompt)?.let { learned ->
            return GatewayResult(learned.answer, "memory", "knowledge:${learned.id}", emptyList(), learned.source, learned.id, true, true)
        }

        // 2. Sem LLM local embutido: o Brain consulta somente capacidades externas
        // disponíveis quando a memória não resolve a solicitação.
        val attempts = mutableListOf<String>()
        val catalog = ApiCatalogRegistry.current() ?: error("Catálogo de APIs não instalado")
        val dynamic = catalog as? DynamicFreeApiCatalog
        val tried = mutableSetOf<String>()
        fun decide(): RoutingDecision? = router.decidir(papel, catalog, emptyList())
        val decision = decide()
        val accountPool = AccountPool(
            poolId = "android-${papel.name.lowercase()}",
            capability = papel.name,
            members = catalog.listarPorPapel(papel).groupBy { it.providerId }.map { (providerId, _) ->
                val hasCredential = !keyStore.get(providerId).isNullOrBlank()
                Account(
                    accountId = "android:$providerId",
                    providerId = providerId,
                    displayName = providerId,
                    credentialRef = CredentialRef.of("credential:android:$providerId"),
                    capabilities = setOf(papel.name),
                    priority = 0,
                    health = if (hasCredential) AccountHealth() else AccountHealth().afterFailure(AccountFailureClass.AUTH_FAILURE, java.time.Instant.now())
                )
            },
            selectionPolicy = SelectionPolicy(maxAttempts = 8, allowFallback = true)
        )
        val accountDecision = AccountRouter().route(
            AccountRouteRequest(
                executionId = "android:${papel.name}:${prompt.hashCode()}",
                capability = papel.name,
                pool = accountPool,
                authorizedAccountIds = accountPool.members.map { it.accountId }.toSet(),
                idempotent = true,
                requestedModelId = decision?.escolhido?.modeloId,
                catalog = catalog
            ),
            java.time.Instant.now()
        )
        val allowedProviders = when (accountDecision) {
            is AccountRouteDecision.Selected -> setOf(accountDecision.providerId) + accountDecision.alternatives.map { it.providerId }
            is AccountRouteDecision.Unavailable -> emptySet()
        }
        val ordered = ArrayDeque<ProviderModel>()
        if (decision != null) {
            ordered.addAll((listOf(decision.escolhido) + decision.alternativas).filter { it.providerId in allowedProviders })
        }

        while (ordered.isNotEmpty()) {
            var model = ordered.removeFirst()
            val key = "${model.providerId}::${model.modeloId}"
            if (!tried.add(key)) continue
            val currentModels = dynamic?.refreshProvider(model.providerId).orEmpty()
            val refreshed = currentModels.firstOrNull { it.modeloId == model.modeloId }
            if (refreshed != null) model = refreshed
            else if (currentModels.isNotEmpty()) {
                val replacement = currentModels.firstOrNull { papel in it.papeisSugeridos }
                if (replacement != null && tried.add("${replacement.providerId}::${replacement.modeloId}")) model = replacement
                else {
                    attempts += "$key: modelo removido/indisponível"
                    continue
                }
            }

            val provider = providers.firstOrNull { it.id == model.providerId }
            if (provider == null) {
                attempts += "$key: provider não cadastrado"
                continue
            }
            val baseEndpoint = provider.models.firstOrNull()?.endpoint
            if (baseEndpoint.isNullOrBlank()) {
                attempts += "$key: endpoint ausente"
                continue
            }

            val accountId = "android:${model.providerId}"
            val client = AccountAwareProviderClient(
                delegate = AndroidProviderClient(model.providerId, chatCompletionsEndpoint(baseEndpoint)),
                providerId = model.providerId,
                credentials = CredentialProvider { requestedAccountId, requestedProviderId ->
                    require(requestedAccountId == accountId) { "accountId não autorizado para provider" }
                    val apiKey = keyStore.get(requestedProviderId)?.takeIf { it.isNotBlank() }
                        ?: error("chave não cadastrada")
                    mapOf("Authorization" to "Bearer $apiKey")
                }
            )
            val request = ProviderRequest(model.modeloId, prompt, accountId = accountId)
            val response = ProviderDispatcher(catalog).dispatch(model, client, request).getOrNull()
            if (response != null && response.statusCode in 200..299) {
                val text = extractText(response.body)
                if (text.isNotBlank()) {
                    val source = buildSource(response, model, text)
                    val urls = extractUrls(response.body + "\n" + text)
                    val citations = urls.map { uri -> KnowledgeCitation(uri, text.take(400)) }
                    val knowledge = learning.observeExternal(prompt, text, source, urls, tagsFor(papel, prompt), citations = citations)
                    val verdict = critic.evaluate(knowledge)
                    val validated = when (verdict.decision) {
                        KnowledgeCriticDecision.ACCEPT -> learning.confirm(knowledge.id, verdict.confidence, source) != null
                        KnowledgeCriticDecision.REJECT -> false
                        KnowledgeCriticDecision.UNCERTAIN -> false
                    }
                    if (!validated) attempts += "$key: Critic ${verdict.decision.name.lowercase()} (${verdict.reason})"
                    return GatewayResult(
                        text = text,
                        providerId = model.providerId,
                        modelId = model.modeloId,
                        attempts = attempts,
                        source = source,
                        knowledgeId = knowledge.id,
                        fromMemory = false,
                        knowledgeValidated = validated,
                        costClass = model.cost
                    )
                }
                attempts += "$key: resposta sem conteúdo"
            } else attempts += "$key: HTTP ${response?.statusCode ?: 0}"
        }

        throw IllegalStateException("Brain não conseguiu responder. Tentativas: ${attempts.joinToString(" | ")}")
    }

    fun confirmKnowledge(knowledgeId: String, confidence: Double, source: KnowledgeSource? = null): KnowledgeEntry? =
        learning.confirm(knowledgeId, confidence, source)

    fun correctKnowledge(knowledgeId: String, correctedAnswer: String, confidence: Double): KnowledgeEntry? =
        learning.correct(knowledgeId, correctedAnswer, confidence)

    private fun buildSource(response: ProviderResponse, model: ProviderModel, text: String): KnowledgeSource {
        val urls = extractUrls(response.body + "\n" + text)
        val github = urls.firstOrNull { it.contains("github.com/", ignoreCase = true) }
        val parts = github?.let(::parseGithub)
        return KnowledgeSource(if (github != null) "github" else "api", github ?: urls.firstOrNull(), response.providerId, model.modeloId, parts?.repository, parts?.path, parts?.commit)
    }

    private data class GithubSource(val repository: String?, val path: String?, val commit: String?)

    private fun parseGithub(url: String): GithubSource? = runCatching {
        val clean = url.substringBefore('#').substringBefore('?').trimEnd('/')
        val marker = "github.com/"
        val start = clean.indexOf(marker, ignoreCase = true)
        if (start < 0) return@runCatching null
        val parts = clean.substring(start + marker.length).split('/').filter { it.isNotBlank() }
        if (parts.size < 2) return@runCatching null
        val repo = "${parts[0]}/${parts[1]}"
        val path = parts.drop(2).joinToString("/").takeIf { it.isNotBlank() }
        val commit = when {
            parts.getOrNull(2) == "commit" -> parts.getOrNull(3)
            parts.getOrNull(2) == "blob" || parts.getOrNull(2) == "tree" -> parts.getOrNull(3)
            else -> null
        }
        GithubSource(repo, path, commit)
    }.getOrNull()

    private fun extractUrls(value: String): List<String> = Regex("https?://[^\\s\\\"'<>]+", RegexOption.IGNORE_CASE)
        .findAll(value).map { it.value.trimEnd('.', ',', ';', ')', ']', '}') }.distinct().toList()

    private fun tagsFor(papel: PapelPipeline, prompt: String): List<String> =
        (listOf(papel.name.lowercase()) + prompt.lowercase().split(Regex("[^\\p{L}\\p{N}_]+"))
            .filter { it.length >= 4 }.take(12)).distinct()

    private fun chatCompletionsEndpoint(base: String): String {
        val normalized = base.trimEnd('/')
        return if (normalized.endsWith("/chat/completions")) normalized else "$normalized/chat/completions"
    }

    private fun extractText(body: String): String = runCatching {
        val root = JSONObject(body)
        val choices = root.optJSONArray("choices") ?: return@runCatching ""
        val first = choices.optJSONObject(0) ?: return@runCatching ""
        first.optJSONObject("message")?.optString("content")?.takeIf { it.isNotBlank() }
            ?: first.optString("text").takeIf { it.isNotBlank() } ?: ""
    }.getOrDefault("")
}

private class AndroidProviderClient(
    private val providerId: String,
    private val endpoint: String,
    private val timeoutMs: Int = 30_000
) : ProviderClient {
    override fun complete(request: ProviderRequest): Result<ProviderResponse> = runCatching {
        require(request.model.isNotBlank()) { "model não pode ser vazio" }
        require(request.prompt.isNotBlank()) { "prompt não pode ser vazio" }
        val started = System.nanoTime()
        val payload = JSONObject().apply {
            put("model", request.model)
            put("messages", org.json.JSONArray().put(JSONObject().put("role", "user").put("content", request.prompt)))
        }.toString()
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            request.headers.filterKeys { it.lowercase() !in setOf("host", "content-length") }
                .forEach { (key, value) -> setRequestProperty(key, value) }
        }
        try {
            connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            ProviderResponse(status, body, (System.nanoTime() - started) / 1_000_000, providerId)
        } finally { connection.disconnect() }
    }
}
