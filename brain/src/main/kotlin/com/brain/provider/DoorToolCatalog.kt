package com.brain.provider

import com.brain.secretary.DoorPolicy
import com.brain.secretary.DoorScope

/** Catálogo declarativo; não autoriza nem executa tools. */
object DoorToolCatalog {
    private val definitions = listOf(
        tool("network.research", "Pesquisa autorizada na Web", "query"),
        tool("weather", "Consulta dados meteorológicos públicos", "location"),
        tool("br.dados", "Consulta dados públicos brasileiros", "query"),
        tool("br.economia", "Consulta séries econômicas públicas", "query"),
        tool("br.geografia", "Consulta localidades públicas brasileiras", "query"),
        tool("cambio", "Consulta taxa pública de câmbio", "currency"),
        tool("sandbox.diagnose", "Diagnóstico autorizado do sandbox", "query"),
        tool("sandbox.info", "Consulta informações do sandbox", "query"),
        tool("sandbox.health", "Consulta saúde do sandbox", "query"),
        tool("chat.respond", "Produz resposta conversacional", "prompt")
    )

    fun forScope(scope: DoorScope): List<ProviderToolDefinition> = definitions
        .filter { DoorPolicy.allows(scope, it.name) }

    fun definition(capability: String): ProviderToolDefinition? = definitions.firstOrNull { it.name == capability }

    private fun tool(name: String, description: String, parameter: String) = ProviderToolDefinition(
        name = name,
        description = description,
        parametersJson = "{\"type\":\"object\",\"properties\":{\"$parameter\":{\"type\":\"string\"}},\"additionalProperties\":false}"
    )
}
