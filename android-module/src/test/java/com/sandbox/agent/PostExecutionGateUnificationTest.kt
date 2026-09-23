package com.sandbox.agent

import com.brain.behavior.BehaviorGate
import com.brain.behavior.GateResult
import com.brain.memory.LayeredMemory
import com.brain.planner.PassoPlano
import com.brain.planner.PlanoExecucao
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fase 12 (ver PLANO_CONEXAO_FASE_12.md, seção 2): prova que PostExecutionGate
 * de fato invoca VerificationGate/CriticGate/ReadinessGate/LearningGate — em vez
 * de reimplementar a decisão inline — para impedir que uma futura edição volte a
 * duplicar a lógica em dois lugares sem que este teste acuse a regressão.
 */
class PostExecutionGateUnificationTest {
    @Test
    fun `evaluate invoca os quatro BehaviorGates injetados, sucesso ou falha`() {
        var verificationCalls = 0
        var criticCalls = 0
        var readinessCalls = 0
        var learningCalls = 0

        val gate = PostExecutionGate(
            memory = LayeredMemory(),
            verificationGate = SpyGate(com.brain.behavior.VerificationGate()) { verificationCalls++ },
            criticGate = SpyGate(com.brain.behavior.CriticGate()) { criticCalls++ },
            readinessGate = SpyGate(com.brain.behavior.ReadinessGate()) { readinessCalls++ },
            learningGate = SpyGate(com.brain.behavior.LearningGate()) { learningCalls++ }
        )

        val plan = PlanoExecucao(
            objetivo = "provar unificação dos gates",
            passos = listOf(PassoPlano(id = "s1", capacidade = "cap.teste", criterioSucesso = "deve concluir"))
        )
        // Passo reprovado: caminho de falha, mas os quatro gates continuam sendo
        // consultados incondicionalmente antes da decisão final (é a decoração, não
        // uma checagem só no caminho feliz).
        val cycle = ResultadoCiclo(
            objetivo = plan.objetivo,
            runId = "unification-run",
            passos = listOf(ResultadoPasso(passoId = "s1", status = StatusPasso.REPROVADO))
        )

        gate.evaluate(plan, cycle)

        assertTrue("VerificationGate não foi chamado", verificationCalls > 0)
        assertTrue("CriticGate não foi chamado", criticCalls > 0)
        assertTrue("ReadinessGate não foi chamado", readinessCalls > 0)
        assertTrue("LearningGate não foi chamado", learningCalls > 0)
    }

    @Test
    fun `evaluateLightChat invoca VerificationGate, CriticGate e ReadinessGate`() {
        var verificationCalls = 0
        var criticCalls = 0
        var readinessCalls = 0

        val gate = PostExecutionGate(
            memory = LayeredMemory(),
            verificationGate = SpyGate(com.brain.behavior.VerificationGate()) { verificationCalls++ },
            criticGate = SpyGate(com.brain.behavior.CriticGate()) { criticCalls++ },
            readinessGate = SpyGate(com.brain.behavior.ReadinessGate()) { readinessCalls++ }
        )

        val plan = PlanoExecucao(
            objetivo = "chat",
            passos = listOf(PassoPlano(id = "chat-step", capacidade = "chat.respond", criterioSucesso = "responder"))
        )
        val cycle = ResultadoCiclo(
            objetivo = plan.objetivo,
            runId = "unification-chat-run",
            passos = listOf(ResultadoPasso(passoId = "chat-step", status = StatusPasso.REPROVADO))
        )

        gate.evaluate(plan, cycle, lightChat = true)

        assertTrue("VerificationGate não foi chamado no fast path", verificationCalls > 0)
        assertTrue("CriticGate não foi chamado no fast path", criticCalls > 0)
        assertTrue("ReadinessGate não foi chamado no fast path", readinessCalls > 0)
    }

    /** Delega para o gate real (preserva o comportamento) só contando as chamadas. */
    private class SpyGate<I, O>(
        private val delegate: BehaviorGate<I, O>,
        private val onCall: () -> Unit
    ) : BehaviorGate<I, O> {
        override fun evaluate(input: I): GateResult<O> {
            onCall()
            return delegate.evaluate(input)
        }
    }
}
