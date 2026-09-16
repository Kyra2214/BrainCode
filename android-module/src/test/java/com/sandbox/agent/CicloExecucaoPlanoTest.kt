package com.sandbox.agent

import com.brain.planner.PassoPlano
import com.brain.planner.PlanoExecucao
import com.brain.policy.Decision
import com.brain.policy.ApprovalStore
import com.brain.policy.PolicyBroker
import com.brain.router.DefaultAIRouter
import com.brain.router.InMemoryApiCatalog
import com.brain.router.JanelaLimite
import com.brain.router.PapelPipeline
import com.brain.router.ProviderModel
import com.sandbox.runtime.FileExecutionLogRepository
import com.sandbox.runtime.ManagedSandboxRuntime
import com.sandbox.runtime.SandboxProcessLauncher
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CicloExecucaoPlanoTest {

    private fun passo(
        id: String,
        capacidade: String = "sandbox.hello",
        dependeDe: List<String> = emptyList(),
        papel: PapelPipeline? = null,
        riskClass: com.brain.execution.RiskClass = com.brain.execution.RiskClass.LOW
    ) = PassoPlano(id = id, capacidade = capacidade, criterioSucesso = "passo $id ok", dependeDe = dependeDe, papel = papel, riskClass = riskClass)

    private fun ciclo(
        root: File,
        allowedCapabilities: Collection<String> = listOf("sandbox.hello"),
        actor: String = "agent-1",
        approvalStore: ApprovalStore? = null
    ): CicloExecucaoPlano {
        val logDir = File(root, "logs")
        val runtime = ManagedSandboxRuntime(TestLauncher(root), FileExecutionLogRepository(logDir), sessionId = "session-1")
        val fakeCatalog = CapabilityResolver(mapOf(
            "sandbox.hello" to { params: List<String> -> CapabilityResolver.Resolution.Comando(listOf("echo", "ok") + params) }
        ))
        val sandbox = Sandbox(runtime = runtime, rootfsDir = root, capabilityResolver = fakeCatalog)
        val policyBroker = PolicyBroker(
            allowedCapabilities = allowedCapabilities,
            actorCapabilities = mapOf(actor to allowedCapabilities)
        )
        val apiCatalog = InMemoryApiCatalog(listOf(
            ProviderModel("groq", "modelo-x", listOf(PapelPipeline.EXECUCAO_CODIGO), JanelaLimite())
        ))
        return CicloExecucaoPlano(policyBroker, sandbox, DefaultAIRouter(), apiCatalog, approvalStore = approvalStore)
    }

    @Test
    fun `passo autorizado roda a capacidade e aprova quando validacao nao acusa falha`() {
        val root = Files.createTempDirectory("ciclo-").toFile()
        try {
            val plano = PlanoExecucao(objetivo = "dizer oi", passos = listOf(passo("hello")))
            val resultado = ciclo(root).autorizarEExecutar(plano, runId = "run-1", actor = "agent-1")

            assertTrue(resultado.aprovado)
            val passoResultado = resultado.passos.single()
            assertEquals(StatusPasso.APROVADO, passoResultado.status)
            assertEquals(Decision.ALLOW, passoResultado.decisaoPolicy?.decision)
            assertTrue(passoResultado.execucao is AgentSandboxSession.CommandOutcome.Completed)
        } finally { root.deleteRecursively() }
    }

    @Test
    fun `callback recebe cada passo assim que o ciclo o conclui`() {
        val root = Files.createTempDirectory("ciclo-stream-").toFile()
        try {
            val recebidos = mutableListOf<String>()
            val plano = PlanoExecucao(
                objetivo = "cadeia",
                passos = listOf(
                    passo("primeiro"),
                    passo("segundo", dependeDe = listOf("primeiro"))
                )
            )
            val resultado = ciclo(root).autorizarEExecutar(plano, "run-stream", "agent-1") { recebidos += it.passoId }

            assertTrue(resultado.aprovado)
            assertEquals(listOf("primeiro", "segundo"), recebidos)
        } finally { root.deleteRecursively() }
    }

    @Test
    fun `capacidade nao registrada na policy nega o passo e bloqueia quem depende dele`() {
        val root = Files.createTempDirectory("ciclo-").toFile()
        try {
            val plano = PlanoExecucao(
                objetivo = "cadeia com passo negado",
                passos = listOf(
                    passo("primeiro", capacidade = "sandbox.nao_permitida"),
                    passo("segundo", dependeDe = listOf("primeiro"))
                )
            )
            val resultado = ciclo(root, allowedCapabilities = listOf("sandbox.hello")).autorizarEExecutar(plano, runId = "run-1", actor = "agent-1")

            assertTrue(!resultado.aprovado)
            val (primeiro, segundo) = resultado.passos
            assertEquals(StatusPasso.NEGADO_PELA_POLICY, primeiro.status)
            assertEquals(Decision.DENY, primeiro.decisaoPolicy?.decision)
            assertEquals(StatusPasso.BLOQUEADO_POR_DEPENDENCIA, segundo.status)
        } finally { root.deleteRecursively() }
    }

    @Test
    fun `router so e consultado quando o passo declara papel`() {
        val root = Files.createTempDirectory("ciclo-").toFile()
        try {
            val plano = PlanoExecucao(
                objetivo = "com e sem papel",
                passos = listOf(
                    passo("sem-papel"),
                    passo("com-papel", dependeDe = listOf("sem-papel"), papel = PapelPipeline.EXECUCAO_CODIGO)
                )
            )
            val resultado = ciclo(root).autorizarEExecutar(plano, runId = "run-1", actor = "agent-1")

            assertTrue(resultado.aprovado)
            val (semPapel, comPapel) = resultado.passos
            assertNull(semPapel.decisaoRouter)
            assertNotNull(comPapel.decisaoRouter)
            assertEquals("groq", comPapel.decisaoRouter?.escolhido?.providerId)
        } finally { root.deleteRecursively() }
    }

    /** Roda comandos de verdade no host (não em proot) — mesma técnica do restante de :android-module. */
    @Test
    fun `passo de alto risco pede approval persistido e retoma uma unica vez`() {
        val root = Files.createTempDirectory("ciclo-approval-").toFile()
        try {
            val store = com.brain.policy.FileApprovalStore(File(root, "approvals.jsonl"))
            val plano = PlanoExecucao("operação sensível", listOf(passo("sensitive", capacidade = "sandbox.hello", riskClass = com.brain.execution.RiskClass.HIGH)))
            val pending = ciclo(root, approvalStore = store).autorizarEExecutar(plano, "run-approval", "agent-1")
            val approvalId = pending.passos.single().approvalId
            assertEquals(StatusPasso.AGUARDANDO_APROVACAO, pending.passos.single().status)
            assertNotNull(approvalId)
            store.decide(approvalId!!, approved = true)
            val resumed = ciclo(root, approvalStore = store).retomar(plano, "run-approval", "agent-1", approvalId)
            assertTrue(resumed.aprovado)
            val consumed = ciclo(root, approvalStore = store).retomar(plano, "run-approval", "agent-1", approvalId)
            assertEquals(StatusPasso.NEGADO_PELA_POLICY, consumed.passos.single().status)
        } finally { root.deleteRecursively() }
    }

    private class TestLauncher(private val rootDir: File) : SandboxProcessLauncher {
        override fun launch(command: List<String>, workingDir: String): Process {
            val hostDir = File(rootDir, workingDir.removePrefix("/")).apply { mkdirs() }
            return ProcessBuilder(command).directory(hostDir).start()
        }
    }
}
