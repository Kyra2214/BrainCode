package com.sandbox.agent

import com.brain.behavior.ReadinessStatus
import com.brain.execution.RiskClass
import com.brain.memory.LayeredMemory
import com.brain.planner.PassoPlano
import com.brain.planner.PlanoExecucao
import com.brain.policy.ApprovalRequired
import com.brain.policy.Decision
import com.brain.policy.PolicyDecision
import com.brain.secretary.UserResponse
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Antes desta correção, `evaluateLightChat` aprovava o passo de pesquisa com
 * base só em "tem texto e não está vazio", ignorando o
 * `web-research:quality=` que `WebResearchExecutor` já emitia. Uma pesquisa
 * de qualidade LOW/REJECTED (ex.: snippet de SEO em vez do dado pedido)
 * passava como se fosse confiável — ver relato do usuário sobre a previsão
 * de Macaé.
 */
class PostExecutionGateResearchQualityTest {

    private fun plano(researchId: String = "pesquisar") = PlanoExecucao(
        objetivo = "responder com pesquisa",
        passos = listOf(
            PassoPlano(id = researchId, capacidade = "network.research", criterioSucesso = "pesquisar"),
            PassoPlano(id = "responder", capacidade = "chat.respond", criterioSucesso = "responder")
        )
    )

    private fun allow(capacidade: String) = PolicyDecision(
        decisionId = "d-$capacidade", runId = "run-quality", taskId = "t-$capacidade", actor = "test",
        capability = capacidade, riskClass = RiskClass.LOW, decision = Decision.ALLOW,
        approvalRequired = ApprovalRequired.NONE, sandboxRequired = false, networkAllowed = true,
        filesystemRoots = emptyList(), budget = emptyMap(),
        expiresAt = Instant.now().plusSeconds(60).toString(), reason = "ok"
    )

    private fun cicloComQualidade(qualidade: String?, researchId: String = "pesquisar"): ResultadoCiclo {
        val evidenciaPesquisa = listOfNotNull(
            "web-research:source=exemplo.com",
            qualidade?.let { "web-research:quality=$it" }
        )
        return ResultadoCiclo(
            objetivo = "responder com pesquisa",
            runId = "run-quality",
            passos = listOf(
                ResultadoPasso(
                    passoId = researchId,
                    status = StatusPasso.APROVADO,
                    resultado = "Pesquisa web concluída com 1 fonte(s).",
                    executionEvidence = evidenciaPesquisa,
                    capacidade = "network.research",
                    decisaoPolicy = allow("network.research")
                ),
                ResultadoPasso(
                    passoId = "responder",
                    status = StatusPasso.APROVADO,
                    userResponse = UserResponse(
                        text = "resposta sintetizada a partir da pesquisa",
                        evidence = listOf("chat:secretary:accept", "chat:request:x"),
                        requestId = "req-1"
                    ),
                    executionEvidence = listOf("chat:secretary:accept", "chat:request:x"),
                    capacidade = "chat.respond",
                    decisaoPolicy = allow("chat.respond")
                )
            )
        )
    }

    @Test
    fun `pesquisa com qualidade REJECTED bloqueia readiness em vez de aprovar so por ter texto`() {
        val gate = PostExecutionGate(memory = LayeredMemory())
        val resultado = gate.evaluate(plano(), cicloComQualidade("REJECTED"), lightChat = true)

        assertFalse("verification não deveria passar com pesquisa REJECTED", resultado.verification.passed)
        assertEquals(ReadinessStatus.BLOCKED, resultado.readiness.status)
        val checkPesquisa = resultado.verification.checks.single { it.criterionId == "pesquisar" }
        assertFalse(checkPesquisa.passed)
        assertTrue(checkPesquisa.detail.contains("REJECTED"))
    }

    @Test
    fun `pesquisa com qualidade LOW tambem bloqueia`() {
        val gate = PostExecutionGate(memory = LayeredMemory())
        val resultado = gate.evaluate(plano(), cicloComQualidade("LOW"), lightChat = true)

        assertFalse(resultado.verification.checks.single { it.criterionId == "pesquisar" }.passed)
    }

    @Test
    fun `pesquisa sem tag de qualidade (rede indisponivel) continua aprovando, degradacao operacional`() {
        val gate = PostExecutionGate(memory = LayeredMemory())
        val resultado = gate.evaluate(plano(), cicloComQualidade(null), lightChat = true)

        assertTrue(resultado.verification.checks.single { it.criterionId == "pesquisar" }.passed)
    }

    @Test
    fun `pesquisa com qualidade HIGH aprova normalmente e readiness fica READY`() {
        val gate = PostExecutionGate(memory = LayeredMemory())
        val resultado = gate.evaluate(plano(), cicloComQualidade("HIGH"), lightChat = true)

        assertTrue(resultado.verification.passed)
        assertEquals(ReadinessStatus.READY, resultado.readiness.status)
    }

    @Test
    fun `evidenceId de cada check aponta para o proprio passo, nao sempre o primeiro`() {
        val gate = PostExecutionGate(memory = LayeredMemory())
        val resultado = gate.evaluate(plano(), cicloComQualidade("HIGH"), lightChat = true)

        val idPesquisa = resultado.verification.checks.single { it.criterionId == "pesquisar" }.evidenceId
        val idResponder = resultado.verification.checks.single { it.criterionId == "responder" }.evidenceId
        assertEquals("run-quality:chat-step:pesquisar", idPesquisa)
        assertEquals("run-quality:chat-step:responder", idResponder)
    }
}
