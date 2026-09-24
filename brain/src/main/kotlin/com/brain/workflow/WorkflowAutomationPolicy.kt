package com.brain.workflow

import java.time.Instant
import java.time.ZoneId

/**
 * Regras determinísticas (sem I/O) sobre o que um workflow pode fazer e quando pode ser agendado.
 * Não autoriza: a autorização continua sendo do PolicyBroker via [WorkflowRunPort]. Aqui ficam
 * só os pré-requisitos que o PolicyBroker não enxerga (o que o documento declara, o schedule
 * e o pin de conteúdo).
 */
object WorkflowAutomationPolicy {
    /** Piso do período entre execuções agendadas (15 min). "every 1 minutes" deixa de agendar. */
    const val MIN_SCHEDULE_PERIOD_SECONDS: Long = 900L

    /**
     * `permissions`/`tools`/`effects` do WORKFLOW.md são parseados mas nenhum executor os honra.
     * Enquanto não houver mapeamento declarado → capability, um documento que os declara não
     * roda (nem manual nem agendado): rodar "às cegas" enganaria o operador sobre o que foi feito.
     * Retorna null quando o documento é somente instrução/leitura.
     */
    fun executionRefusal(document: WorkflowDocument): String? {
        val declared = buildList {
            if (document.requiredPermissions.isNotEmpty()) add("permissions")
            if (document.tools.isNotEmpty()) add("tools")
            if (document.effects.isNotEmpty()) add("effects")
        }
        if (declared.isEmpty()) return null
        return "workflow declara ${declared.joinToString(", ")} e ainda não há mapeamento para capabilities"
    }

    /**
     * Motivo pelo qual o schedule do documento NÃO deve ser registrado, ou null.
     * Documento sem schedule (ou "on-demand"/"manual") não tem o que recusar: retorna null.
     */
    fun scheduleRefusal(
        document: WorkflowDocument,
        zone: ZoneId = ZoneId.of("UTC"),
        now: Instant = Instant.now()
    ): String? {
        val expression = document.schedule?.takeIf { it.isNotBlank() } ?: return null
        val schedule = runCatching { WorkflowSchedule(expression) }
            .getOrElse { return "schedule inválido: ${it.message}" }
        val firstResult = runCatching { schedule.nextAfter(now, zone) }
        firstResult.exceptionOrNull()?.let { return "schedule inválido: ${it.message}" }
        val first = firstResult.getOrNull() ?: return null
        val secondResult = runCatching { schedule.nextAfter(first, zone) }
        secondResult.exceptionOrNull()?.let { return "schedule inválido: ${it.message}" }
        val second = secondResult.getOrNull() ?: return "schedule não produz uma segunda execução"
        val period = second.epochSecond - first.epochSecond
        if (period < MIN_SCHEDULE_PERIOD_SECONDS) {
            return "período de ${period}s abaixo do mínimo de ${MIN_SCHEDULE_PERIOD_SECONDS}s"
        }
        return executionRefusal(document)
    }

    /**
     * O schedule foi aprovado para uma versão/conteúdo específicos. Se o documento resolvido
     * agora não for o mesmo (restore, override custom, edição), o schedule não roda.
     * Pin em branco (schedule persistido antes desta regra) também não roda: reabilite.
     */
    fun pinRefusal(scheduled: ScheduledWorkflow, document: WorkflowDocument): String? = when {
        scheduled.contentHash.isBlank() -> "schedule sem pin de conteúdo; reabilite o workflow"
        scheduled.version != document.version -> "versão mudou de ${scheduled.version} para ${document.version}"
        scheduled.contentHash != document.contentHash -> "conteúdo do workflow mudou desde que o schedule foi registrado"
        else -> null
    }
}
