package com.brain.provider

import org.json.JSONObject

data class ParsedToolCall(val name: String, val argumentsJson: String)

/** Validador local: provider nunca é autoridade para autorizar ou executar efeitos. */
object ToolCallValidator {
    fun validate(call: ParsedToolCall, definitions: List<ToolDefinition>): Result<ParsedToolCall> = runCatching {
        require(call.name.isNotBlank()) { "nome da tool vazio" }
        val definition = definitions.firstOrNull { it.name == call.name }
            ?: error("tool não permitida: ${call.name}")
        val arguments = JSONObject(call.argumentsJson)
        val schema = JSONObject(definition.parametersJson)
        val required = schema.optJSONArray("required") ?: return@runCatching call
        for (index in 0 until required.length()) {
            require(arguments.has(required.getString(index))) {
                "argumento obrigatório ausente: ${required.getString(index)}"
            }
        }
        call
    }
}
