package com.brain.behavior

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadinessTest {
    @Test fun `definition of done varia por tipo`() {
        val code = DefinitionOfDone.forKind(WorkKind.CODE)
        val debug = DefinitionOfDone.forKind(WorkKind.DEBUG)
        assertTrue(code.criteria.contains("compilado"))
        assertTrue(debug.criteria.contains("causa localizada"))
    }

    @Test fun `readiness bloqueia estágio concluido sem evidencia`() {
        val input = ReadinessInput(WorkKind.CODE, ReadinessStageName.entries.associateWith { true })
        val report = ReadinessEvaluator().evaluate(input)
        assertEquals(ReadinessStatus.BLOCKED, report.status)
        assertEquals(7, report.stages.size)
        assertTrue(report.blockers.contains("implementation"))
    }

    @Test fun `readiness fica pronto com sete evidencias`() {
        val completed = ReadinessStageName.entries.associateWith { true }
        val evidence = ReadinessStageName.entries.associateWith { listOf("evidence:${it.name}") }
        val report = ReadinessEvaluator().evaluate(ReadinessInput(WorkKind.EXECUTION, completed, evidence))
        assertEquals(ReadinessStatus.READY, report.status)
        assertTrue(report.blockers.isEmpty())
    }

    @Test fun `readiness rejeita evidencia compartilhada entre estagios`() {
        val completed = ReadinessStageName.entries.associateWith { true }
        val evidence = ReadinessStageName.entries.associateWith { listOf("evidence:shared") }
        val failure = runCatching { ReadinessInput(WorkKind.EXECUTION, completed, evidence) }
        assertTrue(failure.isFailure)
    }
}
