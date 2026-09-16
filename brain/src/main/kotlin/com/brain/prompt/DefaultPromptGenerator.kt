package com.brain.prompt

import com.brain.core.Fase
import com.brain.core.Modulo
import com.brain.core.Roadmap
import com.brain.core.Submodulo
import com.brain.core.Tarefa

private const val COMPATIBILIDADE_MINIMA = 0.5
private const val TAXA_SUCESSO_MINIMA_PARA_REUSO = 0.5

class DefaultPromptGenerator : PromptGenerator {
    override suspend fun gerarPromptsPorRoadmap(roadmap: Roadmap, library: PromptLibrary): List<PromptGerado> {
        val resultado = mutableListOf<PromptGerado>()
        for (fase in roadmap.fases) for (modulo in fase.modulos) for (submodulo in modulo.submodulos) for (tarefa in submodulo.tarefas) {
            resultado += gerarParaTarefa(tarefa, fase, modulo, submodulo, roadmap, library)
        }
        return resultado
    }

    override suspend fun gerarPromptDeCorrecao(tarefa: Tarefa, motivoReprovacao: String, library: PromptLibrary): PromptGerado {
        val template = library.buscarPorContexto(tarefa.descricao)
            .map { it to compatibilidade(tarefa.descricao, it) }
            .filter { (it, score) -> it.taxaSucessoEfetiva() >= TAXA_SUCESSO_MINIMA_PARA_REUSO && score >= COMPATIBILIDADE_MINIMA }
            .maxByOrNull { it.second }
            ?.first
        val texto = if (template != null) renderizarCorrecaoComTemplate(tarefa, motivoReprovacao, template) else buildString {
            appendLine(HEADER_DESENVOLVEDOR); appendLine(); appendLine("CORREÇÃO NECESSÁRIA")
            appendLine("Tarefa: ${tarefa.descricao} (id: ${tarefa.id})")
            appendLine("Motivo da reprovação: $motivoReprovacao"); appendLine()
            appendLine("Corrija especificamente o que causou a reprovação acima, sem reabrir escopo já aprovado.")
        }.trim()
        val origem = if (template != null) "ROUTER_TASK:${tarefa.id}:CORRECAO:TEMPLATE:${template.id}" else "ROUTER_TASK:${tarefa.id}:CORRECAO"
        return PromptGerado(tarefaId = tarefa.id, texto = texto, origem = origem)
    }

    private suspend fun gerarParaTarefa(tarefa: Tarefa, fase: Fase, modulo: Modulo, submodulo: Submodulo, roadmap: Roadmap, library: PromptLibrary): PromptGerado {
        val contextoBusca = "${fase.nome} ${modulo.nome} ${submodulo.nome} ${tarefa.descricao} ${roadmap.projectIntent.projectType} ${roadmap.projectIntent.platform ?: ""}"
        val template = library.buscarPorContexto(contextoBusca)
            .map { it to compatibilidade(contextoBusca, it) }
            .filter { (it, score) -> it.taxaSucessoEfetiva() >= TAXA_SUCESSO_MINIMA_PARA_REUSO && score >= COMPATIBILIDADE_MINIMA }
            .maxByOrNull { it.second }
            ?.first
        val texto = if (template != null) renderizarComTemplate(tarefa, fase, modulo, submodulo, roadmap, template) else gerarDoZero(tarefa, fase, modulo, submodulo, roadmap)
        val origem = if (template != null) "ROUTER_TASK:${tarefa.id}:TEMPLATE:${template.id}" else "ROUTER_TASK:${tarefa.id}:GERADO"
        return PromptGerado(tarefaId = tarefa.id, texto = texto, origem = origem)
    }

    private fun compatibilidade(pedido: String, template: PromptTemplate): Double {
        val pedidoTokens = tokenizar(pedido)
        if (pedidoTokens.isEmpty()) return 0.0
        val todos = tokenizar("${template.contextoDeUso} ${template.finalidade} ${template.skillRelacionada.orEmpty()}")
        if (todos.isEmpty()) return 0.0
        val acertos = pedidoTokens.count { token -> todos.any { candidato -> candidato == token || candidato.contains(token) || token.contains(candidato) } }
        val cobertura = acertos.toDouble() / pedidoTokens.size
        val densidade = acertos.toDouble() / todos.distinct().size.coerceAtLeast(1)
        return (cobertura * 0.7 + densidade * 0.3).coerceIn(0.0, 1.0)
    }

    private fun renderizarComTemplate(tarefa: Tarefa, fase: Fase, modulo: Modulo, submodulo: Submodulo, roadmap: Roadmap, template: PromptTemplate): String = buildString {
        appendLine(HEADER_DESENVOLVEDOR); appendLine(); appendLine("TEMPLATE REUTILIZADO: ${template.id} (taxa observada ${"%.0f".format(template.taxaSucessoEfetiva() * 100)}%, amostras ${template.amostrasObservadas})")
        appendLine("INSTRUÇÃO DO TEMPLATE"); appendLine(adaptarTemplate(template.textoTemplate, tarefa.descricao)); appendLine()
        appendCabecalhoContexto(fase, modulo, submodulo, roadmap); appendLine("TAREFA"); appendLine(tarefa.descricao)
    }.trim()

    private fun renderizarCorrecaoComTemplate(tarefa: Tarefa, motivo: String, template: PromptTemplate): String = buildString {
        appendLine(HEADER_DESENVOLVEDOR); appendLine(); appendLine("CORREÇÃO NECESSÁRIA")
        appendLine("Tarefa: ${tarefa.descricao} (id: ${tarefa.id})"); appendLine("Motivo da reprovação: $motivo"); appendLine()
        appendLine("TEMPLATE REUTILIZADO: ${template.id}"); appendLine(adaptarTemplate(template.textoTemplate, tarefa.descricao)); appendLine()
        appendLine("Corrija especificamente o que causou a reprovação acima, sem reabrir escopo já aprovado.")
    }.trim()

    private fun adaptarTemplate(template: String, tarefa: String): String {
        var resultado = template
        Regex("\\{\\{?([A-Za-z0-9_À-ÿ]+)\\}?\\}").findAll(template).map { it.groupValues[1] }.distinct().forEach { nome ->
            val valor = when (nome.uppercase()) {
                "PEDIDO", "OBJETIVO", "TAREFA", "DESCRICAO", "DESCRIÇÃO" -> tarefa
                else -> "[${nome}: definir conforme a tarefa]"
            }
            resultado = resultado.replace(Regex("\\{\\{?$nome\\}?\\}"), valor, ignoreCase = true)
        }
        return resultado
    }

    private fun gerarDoZero(tarefa: Tarefa, fase: Fase, modulo: Modulo, submodulo: Submodulo, roadmap: Roadmap): String = buildString {
        appendLine(HEADER_DESENVOLVEDOR); appendLine(); appendCabecalhoContexto(fase, modulo, submodulo, roadmap)
        appendLine("OBJETIVO"); appendLine(tarefa.descricao); appendLine(); appendLine("IMPLEMENTAÇÃO")
        appendLine("Executar somente o escopo descrito, preservando a arquitetura existente do projeto ${roadmap.projectIntent.projectType}.")
        appendLine("CRITÉRIOS DE CONCLUSÃO"); appendLine("Concluir apenas quando o objetivo desta tarefa for atendido e validado.")
    }.trim()

    private fun StringBuilder.appendCabecalhoContexto(fase: Fase, modulo: Modulo, submodulo: Submodulo, roadmap: Roadmap) {
        appendLine("PROJETO"); appendLine("${roadmap.projectIntent.projectType} (${roadmap.projectIntent.platform ?: "plataforma não informada"})"); appendLine()
        appendLine("FASE"); appendLine(fase.nome); appendLine(); appendLine("MÓDULO"); appendLine(modulo.nome); appendLine(); appendLine("SUBMÓDULO"); appendLine(submodulo.nome); appendLine()
    }

    private fun PromptTemplate.taxaSucessoEfetiva(): Double = if (amostrasObservadas > 0) taxaSucesso else 0.5
    private fun tokenizar(texto: String): Set<String> = texto.lowercase().split(Regex("[^\\p{L}\\p{N}]+" )).filter { it.length > 2 }.toSet()

    companion object {
        const val HEADER_DESENVOLVEDOR = """MODO DE EXECUÇÃO SILENCIOSA
FASE → MÓDULO → SUBMÓDULO
- Executar sempre do menor peso para o maior peso
- Trabalhar em apenas 1 submódulo por vez
- Documentação obrigatória ao concluir cada submódulo
- Testes somente no fechamento da Fase
- Parar imediatamente ao concluir o escopo
- Economia de tokens
- Preservação da arquitetura existente
- Nenhuma invenção
- Nenhuma narração intermediária

NÃO finalize a tarefa nem considere o trabalho concluído antes de executar os testes e a validação do resultado."""
    }
}
