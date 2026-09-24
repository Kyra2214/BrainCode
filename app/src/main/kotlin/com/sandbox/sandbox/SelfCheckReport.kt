package com.sandbox.sandbox

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Modelo de dados do "Teste geral" — puro (sem Android, sem execução), pra
 * poder ser testado em JVM comum. Quem preenche os itens é o chamador
 * (SandboxViewModel), que já tem acesso ao ToolchainManager/PluginManager/
 * estado da mini-LLM; esta classe só sabe agregar e renderizar.
 */
enum class SelfCheckStatus { OK, WARNING, FAILED }

data class SelfCheckItem(
    val name: String,
    val status: SelfCheckStatus,
    /** Detalhe curto (versão, motivo da falha, tamanho etc.) — vai para o relatório, não pra UI de resumo. */
    val detail: String = ""
)

data class SelfCheckSection(
    val title: String,
    val items: List<SelfCheckItem>
) {
    val okCount: Int get() = items.count { it.status == SelfCheckStatus.OK }
    val warningCount: Int get() = items.count { it.status == SelfCheckStatus.WARNING }
    val failedCount: Int get() = items.count { it.status == SelfCheckStatus.FAILED }
}

data class SelfCheckReport(
    val generatedAt: Long,
    val sections: List<SelfCheckSection>
) {
    val totalItems: Int get() = sections.sumOf { it.items.size }
    val totalOk: Int get() = sections.sumOf { it.okCount }
    val totalWarnings: Int get() = sections.sumOf { it.warningCount }
    val totalFailed: Int get() = sections.sumOf { it.failedCount }
    val allOk: Boolean get() = totalFailed == 0

    /** Relatório em Markdown — pensado pra ser copiado/salvo como .md. */
    fun toMarkdown(): String {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(generatedAt))
        val sb = StringBuilder()
        sb.appendLine("# Relatório de teste geral do Sandbox")
        sb.appendLine()
        sb.appendLine("Gerado em $timestamp")
        sb.appendLine()
        sb.append("**Resumo:** $totalOk/$totalItems OK")
        if (totalWarnings > 0) sb.append(", $totalWarnings aviso(s)")
        if (totalFailed > 0) sb.append(", $totalFailed falha(s)")
        sb.appendLine(".")
        sb.appendLine()
        sections.forEach { section ->
            sb.appendLine("## ${section.title} (${section.okCount}/${section.items.size} OK)")
            sb.appendLine()
            if (section.items.isEmpty()) {
                sb.appendLine("_Nada para checar nesta seção._")
            } else {
                section.items.forEach { item ->
                    val mark = when (item.status) {
                        SelfCheckStatus.OK -> "✅"
                        SelfCheckStatus.WARNING -> "⚠️"
                        SelfCheckStatus.FAILED -> "❌"
                    }
                    sb.append("- $mark **${item.name}**")
                    if (item.detail.isNotBlank()) sb.append(" — ${item.detail}")
                    sb.appendLine()
                }
            }
            sb.appendLine()
        }
        return sb.toString().trimEnd('\n') + "\n"
    }
}
