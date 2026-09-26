package com.sandbox.app

import com.brain.capability.CapabilityAvailability
import com.brain.capability.CapabilityCategory
import com.brain.capability.CapabilityDefinition
import com.brain.capability.CapabilityProvenance
import com.brain.capability.CapabilityProvider
import com.brain.execution.RiskClass
import com.brain.gateway.ActionExecution
import com.brain.gateway.ActionExecutor
import com.brain.gateway.ActionRequest
import com.brain.policy.PolicyDecision
import com.brain.secretary.UserResponse
import java.net.URL
import java.time.Instant
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/** Capabilities de leitura pública, declaradas no registry padrão do Brain. */
class PublicDataCapabilityProvider : CapabilityProvider {
    override val providerId: String = "braincode-public-apis"
    override fun capabilities(): Sequence<CapabilityDefinition> = listOf(
        definition("br.dados", "Dados brasileiros", "Consulta determinística de dados BrasilAPI e ViaCEP", "BrasilAPI/ViaCEP"),
        definition("br.economia", "Economia brasileira", "Consulta séries oficiais do Banco Central do Brasil", "BCB SGS/PTAX"),
        definition("br.geografia", "Geografia brasileira", "Consulta localidades e municípios do IBGE", "IBGE Localidades"),
        definition("weather", "Clima", "Consulta tempo atual usando Open-Meteo", "Open-Meteo"),
        definition("cambio", "Câmbio", "Consulta taxa diária de câmbio Frankfurter com fallback", "Frankfurter")
    ).asSequence()

    private fun definition(id: String, name: String, description: String, source: String) = CapabilityDefinition(
        id = id, name = name, description = description, category = CapabilityCategory.API,
        ownerId = "public-data", origin = providerId, providedCapabilities = setOf(id),
        risk = RiskClass.LOW, cost = com.brain.capability.CostClass.FREE, estimatedLatencyMs = 2_000,
        reliability = .8, quality = .8, supportsWeb = true, availability = CapabilityAvailability.AVAILABLE,
        providerIds = setOf(source.lowercase().replace(Regex("[^a-z0-9]+"), "-")),
        provenance = listOf(CapabilityProvenance(providerId, "public-api", uri = "https://" + source.substringBefore(' ')))
    )
}

abstract class DeterministicApiExecutor(
    protected val http: ApiHttpClient,
    private val serviceName: String
) : ActionExecutor {
    protected fun success(request: ActionRequest, capability: CapabilityDefinition, result: String, sourceUrl: String, detail: String = result): ActionExecution {
        val evidence = listOf("api:$serviceName", "url:$sourceUrl", "data:${detail.take(600)}")
        return ActionExecution(
            success = true, result = result, evidence = evidence,
            provenance = listOf("public-api:$serviceName", "capability:${capability.id}"),
            userResponse = UserResponse(result, evidence, request.actionId, request.parameters["conversationId"])
        )
    }
    protected fun explicitFailure(capability: CapabilityDefinition, message: String) = ActionExecution(
        success = false, error = message,
        evidence = listOf("api:${capability.id}:error"),
        provenance = listOf("public-api:${capability.id}")
    )
    protected fun requiredQuery(request: ActionRequest): String = request.parameters["parameter.0"]?.trim().orEmpty().ifBlank { error("consulta ausente") }
    protected fun json(response: ApiHttpResponse, service: String): String = response.requireSuccess(service)
}

/** BrasilAPI com endpoints comuns; CEP tenta ViaCEP apenas depois de falha/erro de lookup. */
class BrasilApiExecutor(
    http: ApiHttpClient = UrlConnectionApiHttpClient(),
    private val fallbackHttp: ApiHttpClient = http
) : DeterministicApiExecutor(http, "brasilapi" ) {
    override fun execute(request: ActionRequest, capability: CapabilityDefinition, decision: PolicyDecision): ActionExecution = runCatching {
        val query = requiredQuery(request)
        when {
            Regex("(?i)\\bcep\\b").containsMatchIn(query) -> lookupCep(query, request, capability)
            Regex("(?i)\\b(código|codigo)\\s+(do\\s+)?banco\\b|\\bbanco\\b.*\\b\\d{3}\\b").containsMatchIn(query) -> lookupBank(query, request, capability)
            Regex("(?i)\\bferiados?\\b").containsMatchIn(query) -> lookupHolidays(query, request, capability)
            Regex("(?i)\\bddd\\b").containsMatchIn(query) -> lookupDdd(query, request, capability)
            Regex("(?i)\\bcnpj\\b").containsMatchIn(query) -> lookupCnpj(query, request, capability)
            Regex("(?i)\\b(isbn)\\b").containsMatchIn(query) -> lookupIsbn(query, request, capability)
            Regex("(?i)\\bncm\\b").containsMatchIn(query) -> lookupNcm(query, request, capability)
            Regex("(?i)\\bfipe\\b").containsMatchIn(query) -> lookupFipe(query, request, capability)
            Regex("(?i)\\b(taxa|selic|cdi|ipca)\\b").containsMatchIn(query) -> lookupTaxa(query, request, capability)
            else -> error("consulta fora dos casos determinísticos BrasilAPI suportados")
        }
    }.getOrElse { explicitFailure(capability, "BrasilAPI: ${it.message ?: "falha sem detalhe"}") }

    private fun lookupCep(query: String, request: ActionRequest, capability: CapabilityDefinition): ActionExecution {
        val cep = Regex("(?<!\\d)\\d{5}-?\\d{3}(?!\\d)").find(query)?.value?.filter(Char::isDigit) ?: error("informe CEP com 8 dígitos")
        val apiUrl = "https://brasilapi.com.br/api/cep/v2/$cep"
        val body = runCatching {
            val response = http.get(apiUrl)
            if (response.statusCode !in 200..299) error("HTTP ${response.statusCode}")
            JSONObject(json(response, "BrasilAPI CEP"))
        }.recoverCatching {
            val viaUrl = "https://viacep.com.br/ws/$cep/json/"
            val viaResponse = fallbackHttp.get(viaUrl)
            val via = JSONObject(json(viaResponse, "ViaCEP"))
            if (via.optBoolean("erro")) error("CEP não encontrado no ViaCEP")
            via.put("city", via.optString("localidade")); via.put("state", via.optString("uf")); via.put("neighborhood", via.optString("bairro")); via.put("street", via.optString("logradouro")); via
        }.getOrThrow()
        val city = body.optString("city").ifBlank { body.optString("localidade") }
        val state = body.optString("state").ifBlank { body.optString("uf") }
        val street = body.optString("street").ifBlank { body.optString("logradouro") }
        val district = body.optString("neighborhood").ifBlank { body.optString("bairro") }
        val result = buildString { append("CEP ${body.optString("cep", cep)}: "); append(listOf(street, district, city, state).filter { it.isNotBlank() }.joinToString(", ").ifBlank { "endereço sem campos disponíveis" }) }
        val source = if (body.has("service")) apiUrl else "https://viacep.com.br/ws/$cep/json/"
        return success(request, capability, result, source, body.toString())
    }

    private fun lookupBank(query: String, request: ActionRequest, capability: CapabilityDefinition): ActionExecution {
        val code = Regex("(?<!\\d)\\d{3}(?!\\d)").find(query)?.value ?: error("informe o código de 3 dígitos do banco")
        val url = "https://brasilapi.com.br/api/banks/v1/$code"
        val body = JSONObject(json(http.get(url), "BrasilAPI bancos"))
        val name = body.optString("name").ifBlank { body.optString("fullName") }.ifBlank { error("API não retornou nome do banco") }
        return success(request, capability, "Banco $code: $name.", url, body.toString())
    }

    private fun lookupHolidays(query: String, request: ActionRequest, capability: CapabilityDefinition): ActionExecution {
        val year = Regex("\\b(19\\d{2}|20\\d{2}|21\\d{2})\\b").find(query)?.value ?: java.time.Year.now().value.toString()
        val uf = Regex("(?i)\\b(?:em|no estado de)\\s+([A-Z]{2})\\b").find(query)?.groupValues?.getOrNull(1)?.uppercase()
        val url = "https://brasilapi.com.br/api/feriados/v1/$year" + (uf?.let { "?uf=$it" } ?: "")
        val array = JSONArray(json(http.get(url), "BrasilAPI feriados"))
        if (array.length() == 0) error("nenhum feriado retornado para $year")
        val todos = (0 until array.length()).mapNotNull { i -> array.optJSONObject(i) }
        // A BrasilAPI marca tudo como type=national, inclusive datas móveis/comemorativas que NÃO são
        // feriado nacional federal obrigatório (Carnaval, Páscoa, Corpus Christi).
        val nacionais = todos.filter { isFeriadoNacionalOficial(it.optString("name")) }
        if (nacionais.isEmpty()) error("BrasilAPI não retornou feriados nacionais oficiais para $year")
        val items = nacionais.map { "${it.optString("date")}: ${it.optString("name")}".trim() }
        val descartados = todos.filterNot { isFeriadoNacionalOficial(it.optString("name")) }.map { it.optString("name") }
        val nota = if (descartados.isNotEmpty()) " (a API também retornou datas não oficiais — ${descartados.joinToString(", ")} — não incluídas por não serem feriado nacional federal)" else ""
        return success(request, capability, "Feriados nacionais de $year: ${items.joinToString("; ")}.$nota", url, array.toString())
    }

    /** Lista fechada dos feriados nacionais federais oficiais do Brasil. */
    private fun isFeriadoNacionalOficial(nome: String): Boolean {
        val normalizado = nome.lowercase(Locale.ROOT)
            .replace(Regex("[áàâã]"), "a").replace(Regex("[éê]"), "e")
            .replace(Regex("[íî]"), "i").replace(Regex("[óôõ]"), "o").replace(Regex("[úû]"), "u")
            .replace("ç", "c")
        val oficiais = listOf(
            "confraternizacao universal", "confraternizacao mundial", "ano novo",
            "sexta-feira santa", "sexta feira santa", "paixao de cristo",
            "tiradentes", "dia do trabalho", "dia mundial do trabalho",
            "independencia do brasil", "independencia", "nossa senhora aparecida",
            "finados", "proclamacao da republica",
            "dia nacional de zumbi e da consciencia negra", "consciencia negra", "natal"
        )
        return oficiais.any { normalizado.contains(it) }
    }

    private fun lookupDdd(query: String, request: ActionRequest, capability: CapabilityDefinition): ActionExecution {
        val ddd = Regex("(?i)\\bddd\\D{0,12}(\\d{2})\\b|\\b(\\d{2})\\b\\D{0,12}ddd\\b").find(query)?.let { it.groupValues.drop(1).firstOrNull(String::isNotBlank) } ?: error("informe o DDD de dois dígitos")
        val url = "https://brasilapi.com.br/api/ddd/v1/$ddd"
        val body = JSONObject(json(http.get(url), "BrasilAPI DDD"))
        val state = body.optString("state"); val cities = body.optJSONArray("cities")
        val names = cities?.let { (0 until it.length()).map(it::optString) }.orEmpty()
        val result = "DDD $ddd — ${state.ifBlank { "UF não informada" }}: ${names.take(30).joinToString(", ").ifBlank { "municípios não retornados" }}${if (names.size > 30) " e outros ${names.size - 30}" else ""}."
        return success(request, capability, result, url, body.toString())
    }

    private fun lookupCnpj(query: String, request: ActionRequest, capability: CapabilityDefinition): ActionExecution {
        val cnpj = query.replace(Regex("(?i).*?cnpj\\s*[:#]?\\s*"), "").filter(Char::isLetterOrDigit).take(14)
        if (cnpj.length != 14) error("informe CNPJ com 14 caracteres")
        val url = "https://brasilapi.com.br/api/cnpj/v1/$cnpj"
        val body = JSONObject(json(http.get(url), "BrasilAPI CNPJ"))
        val name = body.optString("razao_social").ifBlank { body.optString("nome_fantasia") }
        if (name.isBlank()) error("API não retornou razão social")
        return success(request, capability, "CNPJ $cnpj: $name; situação ${body.optString("descricao_situacao_cadastral", body.optString("situacao_cadastral"))}; município ${body.optString("municipio")}/${body.optString("uf")}.", url, body.toString())
    }

    private fun lookupIsbn(query: String, request: ActionRequest, capability: CapabilityDefinition): ActionExecution {
        val isbn = query.substringAfter(Regex("(?i)isbn").find(query)?.value.orEmpty()).filter(Char::isDigit).takeIf { it.length == 10 || it.length == 13 } ?: error("informe ISBN-10 ou ISBN-13")
        val url = "https://brasilapi.com.br/api/isbn/v1/$isbn"
        val body = JSONObject(json(http.get(url), "BrasilAPI ISBN"))
        val authors = body.optJSONArray("authors")?.let { (0 until it.length()).joinToString(", ") { i -> it.optString(i) } }.orEmpty()
        return success(request, capability, "${body.optString("title").ifBlank { error("título não retornado") }}${if (authors.isNotBlank()) " — $authors" else ""} (ISBN $isbn).", url, body.toString())
    }

    private fun lookupNcm(query: String, request: ActionRequest, capability: CapabilityDefinition): ActionExecution {
        val code = Regex("(?<!\\d)\\d{8}(?!\\d)").find(query)?.value ?: error("informe o código NCM de 8 dígitos")
        val url = "https://brasilapi.com.br/api/ncm/v1/$code"
        val body = JSONObject(json(http.get(url), "BrasilAPI NCM"))
        val description = body.optString("descricao")
        if (description.isBlank()) error("NCM sem descrição")
        return success(request, capability, "NCM $code: $description.", url, body.toString())
    }

    private fun lookupFipe(query: String, request: ActionRequest, capability: CapabilityDefinition): ActionExecution {
        val code = Regex("(?i)\\b\\d{6,8}\\b").find(query)?.value ?: error("para consulta FIPE, informe o código FIPE do veículo")
        val url = "https://brasilapi.com.br/api/fipe/preco/v1/$code"
        val body = JSONArray(json(http.get(url), "BrasilAPI FIPE"))
        if (body.length() == 0) error("código FIPE não encontrado")
        val item = body.optJSONObject(0) ?: error("resposta FIPE inválida")
        val result = "${item.optString("Marca")} ${item.optString("Modelo")} (${item.optString("AnoModelo")}) — ${item.optString("Valor")}, referência ${item.optString("MesReferencia")}."
        return success(request, capability, result, url, item.toString())
    }

    private fun lookupTaxa(query: String, request: ActionRequest, capability: CapabilityDefinition): ActionExecution {
        val ticker = Regex("(?i)\\b(SELIC|CDI|IPCA|IGP-M|TR)\\b").find(query)?.value?.uppercase() ?: error("informe uma taxa (Selic, CDI, IPCA, IGP-M ou TR)")
        val url = "https://brasilapi.com.br/api/taxas/v1/${ticker.encodeQuery()}"
        val body = JSONObject(json(http.get(url), "BrasilAPI taxas"))
        val name = body.optString("nome").ifBlank { ticker }
        val value = body.opt("valor")?.toString()?.takeIf { it != "null" } ?: error("valor da taxa ausente")
        return success(request, capability, "$name: $value (BrasilAPI).", url, body.toString())
    }
}

/** Consultas de localidade oficiais via IBGE; resposta nunca é sintetizada a partir de uma lista fixa. */
class IbgeExecutor(http: ApiHttpClient = UrlConnectionApiHttpClient()) : DeterministicApiExecutor(http, "ibge-localidades") {
    override fun execute(request: ActionRequest, capability: CapabilityDefinition, decision: PolicyDecision): ActionExecution = runCatching {
        val query = requiredQuery(request)
        val uf = Regex("(?i)\\b([A-Z]{2})\\b").find(query)?.value?.uppercase()
        val municipios = Regex("(?i)\\b(municípios|municipios|cidades)\\b").containsMatchIn(query)
        val endpoint = if (municipios && uf != null) "estados/${uf}/municipios" else if (municipios) "municipios" else if (uf != null) "estados/$uf" else "estados"
        val url = "https://servicodados.ibge.gov.br/api/v1/localidades/$endpoint"
        val body = json(http.get(url), "IBGE Localidades")
        val values = if (body.trimStart().startsWith("[")) JSONArray(body) else JSONArray().put(JSONObject(body))
        if (values.length() == 0) error("IBGE não retornou localidades")
        val names = (0 until minOf(values.length(), 80)).mapNotNull { i -> values.optJSONObject(i)?.let { item ->
            val name = item.optString("nome"); val sigla = item.optString("sigla")
            when { name.isBlank() -> null; sigla.isNotBlank() -> "$name ($sigla)"; else -> name }
        } }
        val label = if (municipios) "Municípios" else "Unidades da Federação"
        val result = "$label consultados no IBGE: ${names.joinToString(", ")}${if (values.length() > 80) " (primeiros 80 de ${values.length()})" else ""}."
        success(request, capability, result, url, body.take(5_000))
    }.getOrElse { explicitFailure(capability, "IBGE: ${it.message ?: "falha sem detalhe"}") }
}

/** SELIC e PTAX via séries oficiais SGS: SELIC 432, dólar comercial venda 1. */
class BcbExecutor(http: ApiHttpClient = UrlConnectionApiHttpClient()) : DeterministicApiExecutor(http, "bcb-sgs") {
    override fun execute(request: ActionRequest, capability: CapabilityDefinition, decision: PolicyDecision): ActionExecution = runCatching {
        val query = requiredQuery(request)
        val selic = Regex("(?i)\\bselic\\b").containsMatchIn(query)
        val series = Regex("(?i)\\b(\\d{2,6})\\b").find(query)?.groupValues?.get(1)?.toIntOrNull()
            ?: if (selic) 432 else if (Regex("(?i)(dólar|dolar|ptax)").containsMatchIn(query)) 1 else error("informe SELIC, PTAX/dólar ou um código SGS")
        val url = "https://api.bcb.gov.br/dados/serie/bcdata.sgs.$series/dados/ultimos/1?formato=json"
        val values = JSONArray(json(http.get(url), "BCB SGS"))
        if (values.length() == 0) error("BCB não retornou observações recentes para a série $series")
        val item = values.optJSONObject(values.length() - 1) ?: error("resposta BCB inválida")
        val date = item.optString("data"); val value = item.optString("valor")
        if (date.isBlank() || value.isBlank()) error("BCB retornou observação sem data ou valor")
        val label = when (series) { 432 -> "SELIC meta"; 1 -> "Dólar comercial (PTAX, venda)"; else -> "Série SGS $series" }
        val result = "$label: $value em $date (BCB)."
        success(request, capability, result, url, item.toString())
    }.getOrElse { explicitFailure(capability, "BCB: ${it.message ?: "falha sem detalhe"}") }
}

/** Geocodifica a cidade e só então consulta a previsão atual, ambas as APIs Open-Meteo. */
class WeatherExecutor(http: ApiHttpClient = UrlConnectionApiHttpClient()) : DeterministicApiExecutor(http, "open-meteo") {
    override fun execute(request: ActionRequest, capability: CapabilityDefinition, decision: PolicyDecision): ActionExecution = runCatching {
        val query = requiredQuery(request)
        val location = Regex("(?i)\\b(?:em|de)\\s+(.+?)(?=\\s+(?:hoje|agora|amanhã|amanha|neste momento)\\b|[?!.;,]|$)").find(query)?.groupValues?.get(1)?.trim()
            ?: Regex("(?i)(?:tempo|clima)\\s+(?:em|de)\\s+(.+?)(?=[?!.;,]|$)").find(query)?.groupValues?.get(1)?.trim()
            ?: error("informe a cidade para consultar o clima")
        val geoUrl = "https://geocoding-api.open-meteo.com/v1/search?name=${location.encodeQuery()}&count=1&language=pt&format=json&countryCode=BR"
        val geoRoot = JSONObject(json(http.get(geoUrl), "Open-Meteo geocoding"))
        val places = geoRoot.optJSONArray("results") ?: error("cidade não encontrada no Open-Meteo")
        val place = places.optJSONObject(0) ?: error("cidade não encontrada no Brasil")
        val lat = place.optDouble("latitude", Double.NaN); val lon = place.optDouble("longitude", Double.NaN)
        if (!lat.isFinite() || !lon.isFinite()) error("Open-Meteo retornou coordenadas inválidas")
        val placeName = listOf(place.optString("name"), place.optString("admin1")).filter { it.isNotBlank() }.distinct().joinToString(" - ")
        val weatherUrl = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,apparent_temperature,relative_humidity_2m,precipitation,weather_code,wind_speed_10m&timezone=auto&forecast_days=1"
        val root = JSONObject(json(http.get(weatherUrl), "Open-Meteo forecast"))
        val current = root.optJSONObject("current") ?: error("Open-Meteo não retornou condições atuais")
        val units = root.optJSONObject("current_units")
        val temp = current.opt("temperature_2m")?.toString() ?: error("temperatura indisponível")
        val tempUnit = units?.optString("temperature_2m").orEmpty()
        val apparent = current.opt("apparent_temperature")?.toString()?.let { "sensação de $it${units?.optString("apparent_temperature").orEmpty()}" }
        val humidity = current.opt("relative_humidity_2m")?.toString()?.let { "umidade $it%" }
        val wind = current.opt("wind_speed_10m")?.toString()?.let { "$it ${units?.optString("wind_speed_10m").orEmpty()} de vento" }
        val precipitation = current.opt("precipitation")?.toString()?.let { "$it ${units?.optString("precipitation").orEmpty()} de precipitação" }
        val code = current.optInt("weather_code", -1)
        val description = weatherDescription(code)
        val result = buildList { add("Agora em $placeName: $temp$tempUnit"); apparent?.let(::add); description?.let(::add); humidity?.let(::add); wind?.let(::add); precipitation?.let(::add) }.joinToString(", ") + "."
        success(request, capability, result, weatherUrl, current.toString())
    }.getOrElse { explicitFailure(capability, "Clima indisponível: ${it.message ?: "falha sem detalhe"}") }

    private fun weatherDescription(code: Int): String? = when (code) {
        0 -> "céu limpo"; 1 -> "predominantemente limpo"; 2 -> "parcialmente nublado"; 3 -> "nublado";
        45, 48 -> "nevoeiro"; 51, 53, 55 -> "garoa"; 56, 57 -> "garoa congelante";
        61, 63, 65 -> "chuva"; 66, 67 -> "chuva congelante"; 71, 73, 75, 77 -> "neve";
        80, 81, 82 -> "pancadas de chuva"; 85, 86 -> "pancadas de neve"; 95, 96, 99 -> "trovoada"; else -> null
    }
}

/** Câmbio Frankfurter first; fallback static currency-api only on HTTP/parse/no-data failure. */
class ExchangeRateExecutor(
    http: ApiHttpClient = UrlConnectionApiHttpClient(),
    private val fallbackHttp: ApiHttpClient = http
) : DeterministicApiExecutor(http, "frankfurter") {
    override fun execute(request: ActionRequest, capability: CapabilityDefinition, decision: PolicyDecision): ActionExecution = runCatching {
        val query = requiredQuery(request)
        val pair = CurrencyPair.parse(query)
        val primaryUrl = "https://api.frankfurter.dev/v2/rate/${pair.base.lowercase()}/${pair.quote.lowercase()}"
        val rate = runCatching {
            val obj = JSONObject(json(http.get(primaryUrl), "Frankfurter"))
            val date = obj.optString("date"); val value = obj.optDouble("rate", Double.NaN)
            if (!value.isFinite() || value <= 0) error("Frankfurter sem taxa válida")
            Triple(value, date, primaryUrl)
        }.recoverCatching {
            val fallbackUrl = "https://cdn.jsdelivr.net/npm/@fawazahmed0/currency-api@latest/v1/currencies/${pair.base.lowercase()}/${pair.quote.lowercase()}.min.json"
            val obj = JSONObject(json(fallbackHttp.get(fallbackUrl), "currency-api"))
            val value = obj.optJSONObject(pair.base.lowercase())?.optDouble(pair.quote.lowercase(), Double.NaN) ?: Double.NaN
            if (!value.isFinite() || value <= 0) error("currency-api sem taxa válida")
            Triple(value, obj.optString("date"), fallbackUrl)
        }.getOrThrow()
        val conversion = pair.amount?.let { amount -> "${format(amount)} ${pair.base} = ${format(amount * rate.first)} ${pair.quote}; " }.orEmpty()
        val result = "${conversion}1 ${pair.base} = ${format(rate.first)} ${pair.quote}${rate.second.takeIf { it.isNotBlank() }?.let { " (data: $it)" }.orEmpty()} (taxa indicativa diária)."
        success(request, capability, result, rate.third, result)
    }.getOrElse { explicitFailure(capability, "Câmbio indisponível: ${it.message ?: "falha sem detalhe"}") }

    private fun format(value: Double) = java.text.DecimalFormat("#,##0.####", java.text.DecimalFormatSymbols(java.util.Locale("pt", "BR"))).format(value)
}

private data class CurrencyPair(val base: String, val quote: String, val amount: Double?) {
    companion object {
        private val codes = mapOf("dólar" to "USD", "dolar" to "USD", "usd" to "USD", "real" to "BRL", "reais" to "BRL", "brl" to "BRL", "euro" to "EUR", "eur" to "EUR", "libra" to "GBP", "gbp" to "GBP", "iene" to "JPY", "jpy" to "JPY", "peso argentino" to "ARS", "ars" to "ARS")
        fun parse(query: String): CurrencyPair {
            val lower = query.lowercase()
            val amount = Regex("(?<!\\w)(\\d+(?:[.,]\\d+)?)").find(lower)?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull()
            val found = codes.entries.filter { Regex("(?i)\\b${Regex.escape(it.key)}\\b").containsMatchIn(lower) }.distinctBy { it.value }.map { it.value }
            val base = found.firstOrNull() ?: error("informe a moeda de origem")
            val quote = when {
                found.size >= 2 -> found[1]
                base == "BRL" -> "USD"
                else -> "BRL"
            }
            if (base == quote) error("moedas de origem e destino devem ser diferentes")
            return CurrencyPair(base, quote, amount)
        }
    }
}
