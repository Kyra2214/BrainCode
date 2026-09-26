package com.brain.intent

import com.brain.secretary.Door
import com.brain.secretary.DeterministicSecretary
import com.brain.secretary.OrderIntent
import com.brain.capability.CapabilityRegistry
import com.brain.text.IntentNegation
import com.brain.text.TriggerLexicon
import com.brain.text.InformationalQuestionClassifier
import com.brain.research.ResearchIntentClassifier
import java.util.Locale

/** Categorias observáveis da intenção antes de qualquer planejamento ou execução. */
enum class IntentCategory {
    WEATHER,
    CALCULATION,
    NAVIGATION,
    RESEARCH,
    CODE_EXECUTION,
    CREATION,
    INFORMATION,
    CONVERSATION,
    AMBIGUOUS
}

enum class Route {
    CAPABILITY,
    CONVERSATION,
    CREATION,
    CLARIFY,
    LLM_FALLBACK
}

enum class EnvelopeSource {
    DETERMINISTIC,
    LLM_FALLBACK
}

data class IntentEnvelope(
    val rawText: String,
    val door: Door,
    val intent: IntentCategory,
    val action: String?,
    val entities: Map<String, String>,
    val requiresLiveData: Boolean,
    val route: Route,
    val targetCapability: String?,
    val confidence: Double,
    val source: EnvelopeSource,
    val rationale: String
) {
    init {
        require(rawText.isNotBlank()) { "rawText não pode ser vazio" }
        require(confidence in 0.0..1.0) { "confidence deve estar entre 0 e 1" }
        require(route != Route.CAPABILITY || !targetCapability.isNullOrBlank()) {
            "rota CAPABILITY precisa de targetCapability"
        }
        require(route != Route.LLM_FALLBACK || source == EnvelopeSource.LLM_FALLBACK) {
            "rota LLM_FALLBACK precisa registrar sua origem"
        }
    }
}

/** Única autoridade que transforma sinais determinísticos em uma rota executável. */
class BrainRouter {
    fun resolveCapability(envelope: IntentEnvelope, registry: CapabilityRegistry): String? {
        val target = envelope.targetCapability ?: return null
        return registry.findByCapability(target).firstOrNull()?.let { definition ->
            when {
                definition.id == target -> definition.id
                definition.providedCapabilities.contains(target) -> target
                else -> null
            }
        }
    }

    fun decide(
        door: Door,
        action: String?,
        targetCapability: String?,
        confidence: Double,
        ambiguous: Boolean
    ): Route = when {
        ambiguous -> Route.CLARIFY
        door == Door.CREATE || door == Door.PROMPT -> Route.CREATION
        !action.isNullOrBlank() && !targetCapability.isNullOrBlank() -> Route.CAPABILITY
        confidence < 0.4 -> Route.CONVERSATION
        else -> Route.CONVERSATION
    }
}

/**
 * Interpretador local e determinístico. Ele compõe sinais dos léxicos existentes,
 * mas nenhum provedor individual decide a rota por conta própria.
 */
class BrainInputInterpreter(
    private val secretary: DeterministicSecretary = DeterministicSecretary(),
    private val router: BrainRouter = BrainRouter()
) {
    fun interpret(text: String, designatedIntent: OrderIntent? = null): IntentEnvelope {
        val raw = text.trim()
        require(raw.isNotBlank()) { "texto não pode ser vazio" }
        val normalized = raw.lowercase(Locale.ROOT)
        val orderIntent = designatedIntent ?: secretary.classify(raw)
        val weather = isWeather(normalized)
        val brazilData = isBrazilData(normalized)
        val brazilEconomy = isBrazilEconomy(normalized)
        val brazilGeography = isBrazilGeography(normalized)
        val currency = isCurrencyConversion(normalized)
        val specificLiveData = brazilData || brazilEconomy || brazilGeography || currency
        val calculation = isCalculation(normalized) && !specificLiveData
        val navigation = isNavigation(normalized)
        val research = !weather && !calculation && !navigation &&
            IntentNegation.hasAllowedOccurrence(normalized, TriggerLexicon.VERBOS_PESQUISA)
        val domainSearch = !weather && !calculation && !navigation && !brazilData && !brazilEconomy && !brazilGeography && !currency && !research &&
            ResearchIntentClassifier.requiresWebSearch(normalized)
        val codeExecution = !weather && !calculation && !navigation && !brazilData && !brazilEconomy && !brazilGeography && !currency && !research && !domainSearch &&
            IntentNegation.hasAllowedOccurrence(normalized, TriggerLexicon.EXECUTION_TERMS) &&
            TriggerLexicon.matches(normalized, listOf("código", "codigo", "script", "programa", "função", "funcao"))
        val information = !weather && !calculation && !navigation && !brazilData && !brazilEconomy && !brazilGeography && !currency && !research && !domainSearch && !codeExecution &&
            isInformationalQuestion(normalized)
        val ambiguous = normalized.matches(Regex("(?i)^(faça|faca|execute|rode|fa\u00e7a|fazer) isso[.!? ]*$"))

        val intent: IntentCategory
        val action: String?
        val target: String?
        val entities = linkedMapOf<String, String>()
        val requiresLiveData: Boolean
        val confidence: Double
        val rationale: String

        when {
            orderIntent.door == Door.CREATE -> {
                intent = IntentCategory.CREATION
                action = "CREATE_ARTIFACT"
                target = "brain.plan"
                requiresLiveData = false
                confidence = 1.0
                rationale = "a Porta CREATE foi designada pelo Secretário"
            }
            orderIntent.door == Door.PROMPT -> {
                intent = IntentCategory.CREATION
                action = "CREATE_PROMPT"
                target = "prompt.library.write"
                requiresLiveData = false
                confidence = 1.0
                rationale = "a Porta PROMPT foi designada pelo Secretário"
            }
            ambiguous -> {
                intent = IntentCategory.AMBIGUOUS
                action = null
                target = null
                requiresLiveData = false
                confidence = 0.5
                rationale = "a ordem usa um pronome sem ação ou objeto resolvido"
            }
            weather -> {
                intent = IntentCategory.WEATHER
                action = "GET_CURRENT_WEATHER"
                target = "weather"
                extractLocation(normalized)?.let { entities["location"] = it }
                extractDate(normalized)?.let { entities["date"] = it }
                requiresLiveData = true
                confidence = 1.0
                rationale = "padrão dedicado de clima/tempo real"
            }
            brazilData -> {
                intent = IntentCategory.RESEARCH
                action = "LOOKUP_BRAZIL_DATA"
                target = "br.dados"
                requiresLiveData = true
                confidence = 1.0
                rationale = "consulta determinística de cadastro/dado brasileiro estruturado"
            }
            brazilEconomy -> {
                intent = IntentCategory.RESEARCH
                action = "LOOKUP_OFFICIAL_ECONOMIC_DATA"
                target = "br.economia"
                requiresLiveData = true
                confidence = 1.0
                rationale = "consulta de série econômica oficial do Banco Central"
            }
            brazilGeography -> {
                intent = IntentCategory.RESEARCH
                action = "LOOKUP_IBGE_LOCALITY"
                target = "br.geografia"
                requiresLiveData = true
                confidence = 1.0
                rationale = "consulta determinística de localidade do IBGE"
            }
            currency -> {
                intent = IntentCategory.RESEARCH
                action = "GET_EXCHANGE_RATE"
                target = "cambio"
                requiresLiveData = true
                confidence = 1.0
                rationale = "consulta determinística de conversão/taxa de câmbio"
            }
            calculation -> {
                intent = IntentCategory.CALCULATION
                action = "CALCULATE"
                target = "chat.respond"
                requiresLiveData = false
                confidence = 1.0
                rationale = "padrão dedicado de cálculo local"
            }
            navigation -> {
                intent = IntentCategory.NAVIGATION
                action = "OPEN_CATALOG"
                target = "sandbox.info"
                requiresLiveData = false
                confidence = 1.0
                rationale = "padrão dedicado de navegação do catálogo"
            }
            research || domainSearch -> {
                intent = IntentCategory.RESEARCH
                action = "RESEARCH"
                target = "network.research"
                requiresLiveData = true
                confidence = 0.85
                rationale = if (research) "verbo explícito de pesquisa permitido pelo léxico" else "pergunta técnica, acadêmica ou factual direcionada por heurística local"
            }
            codeExecution -> {
                intent = IntentCategory.CODE_EXECUTION
                action = "EXECUTE_CODE"
                target = "sandbox.code"
                requiresLiveData = false
                confidence = 0.9
                rationale = "ordem explícita de execução de código sujeita à Policy"
            }
            information -> {
                intent = IntentCategory.INFORMATION
                action = null
                target = null
                requiresLiveData = false
                confidence = 0.9
                rationale = "pedido informativo local sem capability de execução"
            }
            else -> {
                intent = IntentCategory.CONVERSATION
                action = null
                target = null
                requiresLiveData = false
                confidence = if (normalized.length < 4) 0.2 else 0.8
                rationale = "nenhum padrão de capability determinística foi identificado"
            }
        }

        val route = router.decide(orderIntent.door, action, target, confidence, ambiguous)
        return IntentEnvelope(
            rawText = raw,
            door = orderIntent.door,
            intent = intent,
            action = action,
            entities = entities,
            requiresLiveData = requiresLiveData,
            route = route,
            targetCapability = target,
            confidence = confidence,
            source = EnvelopeSource.DETERMINISTIC,
            rationale = rationale
        )
    }

    private fun isWeather(text: String): Boolean =
        (TriggerLexicon.matches(text, TriggerLexicon.TEMAS_TEMPO_REAL) &&
            TriggerLexicon.matches(text, TriggerLexicon.INTERROGATIVOS)) ||
            TriggerLexicon.matches(text, TriggerLexicon.CONSULTAS_TEMPO_REAL_SEM_INTERROGATIVO) ||
            Regex("(?i)\\b(vai chover|quanto está fazendo|quanto esta fazendo)\\b").containsMatchIn(text)

    private fun isCalculation(text: String): Boolean =
        Regex("(?i)\\b(calcul(e|a)|quanto é|quanto e|qual o resultado)\\b").containsMatchIn(text) ||
            Regex("(?i)\\d+(?:[.,]\\d+)?\\s*(?:v|a)\\s*[x×*]\\s*\\d+(?:[.,]\\d+)?\\s*(?:v|a)?\\b").containsMatchIn(text) ||
            Regex("^[0-9\\s.,()+\\-*/×÷xX]+[?!.]*$").matches(text.trim()) &&
                text.any { it.isDigit() } && text.any { it in "+-*/×÷xX" }

    private fun isNavigation(text: String): Boolean =
        Regex("(?i)\\b(ab(r|ra)|abra|abrir|mostre|mostrar|liste|listar)\\b.*\\b(catálogo|catalogo|capacidades|comandos)\\b").containsMatchIn(text)

    private fun isBrazilData(text: String): Boolean =
        Regex("(?i)\\b(cep|cnpj|ddd|isbn|ncm|fipe|feriado(s)?)\\b").containsMatchIn(text) ||
            Regex("(?i)\\bbanco\\b.*\\b(?:código|codigo|número|numero)?\\s*\\d{3}\\b").containsMatchIn(text)

    private fun isBrazilEconomy(text: String): Boolean =
        Regex("(?i)\\b(selic|ptax|taxa(s)? de juros|cdi|ipca|igp-m)\\b").containsMatchIn(text) ||
            Regex("(?i)\\b(cotação|cotacao|valor|preço|preco)\\b.*\\b(dólar|dolar)\\b").containsMatchIn(text)

    private fun isBrazilGeography(text: String): Boolean =
        Regex("(?i)\\b(municípios|municipios|estados|regiões|regioes)\\b.*\\b(ibge|brasil|uf|estado|região|regiao)?\\b").containsMatchIn(text) &&
            Regex("(?i)\\b(lista|listar|quais|quantos|mostre|consult|municípios|municipios|estados|regiões|regioes)\\b").containsMatchIn(text)

    private fun isCurrencyConversion(text: String): Boolean =
        Regex("(?i)\\b(câmbio|cambio|converter|converta|convert(a|er)|em dólar|em dolar|para dólar|para dolar|para usd|para eur)\\b").containsMatchIn(text) ||
            Regex("(?i)\\b\\d+(?:[.,]\\d+)?\\s*(?:brl|usd|eur)\\b.*\\b(?:brl|usd|eur)\\b").containsMatchIn(text)

    private fun isInformationalQuestion(text: String): Boolean =
        InformationalQuestionClassifier.isRecoverable(text)

    private fun extractLocation(text: String): String? {
        val match = Regex("(?i)\\b(?:em|de)\\s+(.+?)(?=\\s+(?:hoje|agora|amanhã|amanha|neste momento)\\b|[?!.;,]|$)").find(text)
        return match?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun extractDate(text: String): String? = when {
        Regex("(?i)\\bhoje\\b").containsMatchIn(text) -> "today"
        Regex("(?i)\\b(amanhã|amanha)\\b").containsMatchIn(text) -> "tomorrow"
        Regex("(?i)\\b(agora|neste momento)\\b").containsMatchIn(text) -> "now"
        else -> null
    }
}
