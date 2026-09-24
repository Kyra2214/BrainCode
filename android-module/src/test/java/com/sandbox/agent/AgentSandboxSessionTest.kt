package com.sandbox.agent

import com.brain.execution.RiskClass
import com.brain.policy.ApprovalRequired
import com.brain.policy.Decision
import com.brain.policy.ExecutionAuthorization
import com.brain.policy.PolicyBroker
import com.brain.policy.PolicyContext
import com.sandbox.runtime.FileExecutionLogRepository
import com.sandbox.runtime.ManagedSandboxRuntime
import com.sandbox.runtime.SandboxProcessLauncher
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.temporal.ChronoUnit
import org.junit.Assert.*
import org.junit.Test

class AgentSandboxSessionTest {

    private fun authorization(
        runId: String = "run-1",
        budget: Map<String, Long> = emptyMap(),
        capability: String = "shell.exec"
    ): ExecutionAuthorization {
        val decision = PolicyBroker(
            allowedCapabilities = listOf(capability),
            actorCapabilities = mapOf("agent-1" to listOf(capability))
        ).authorize(
            actor = "agent-1",
            capability = capability,
            resource = "task-1",
            context = PolicyContext(
                runId = runId,
                taskId = "task-1",
                actor = "agent-1",
                riskClass = RiskClass.LOW,
                approval = ApprovalRequired.NONE,
                budget = budget
            )
        )
        return ExecutionAuthorization.fromDecision(decision)!!
    }

    private fun session(
        rootDir: File,
        authorization: ExecutionAuthorization = authorization(),
        capabilityResolver: CapabilityResolver = CapabilityResolver()
    ): Pair<AgentSandboxSession, File> {
        val logDir = File(rootDir, "logs")
        val runtime = ManagedSandboxRuntime(TestLauncher(rootDir), FileExecutionLogRepository(logDir), sessionId = "session-1")
        val sandbox = Sandbox(runtime = runtime, rootfsDir = rootDir, capabilityResolver = capabilityResolver)
        val opened = sandbox.abrirSessao(authorization)
        return opened to File(rootDir, "home/sandbox/workspace/${authorization.runId}")
    }

    @Test fun `arquivo escrito em um passo e lido no proximo, no mesmo workspace`() {
        val root = Files.createTempDirectory("agent-session-").toFile()
        try {
            val (agentSession, _) = session(root)

            val write = agentSession.escreverArquivo("notas.txt", "primeiro passo")
            assertTrue(write is AgentSandboxSession.FileOutcome.Ok)

            val listed = agentSession.listarArquivos(".")
            assertTrue(listed is AgentSandboxSession.FileOutcome.Ok)
            assertEquals(listOf("notas.txt"), (listed as AgentSandboxSession.FileOutcome.Ok).value)

            val read = agentSession.lerArquivo("notas.txt")
            assertTrue(read is AgentSandboxSession.FileOutcome.Ok)
            assertEquals("primeiro passo", (read as AgentSandboxSession.FileOutcome.Ok).value)
        } finally { root.deleteRecursively() }
    }

    @Test fun `comando rodado na sessao enxerga arquivo escrito antes, no mesmo workspace`() {
        val root = Files.createTempDirectory("agent-session-").toFile()
        try {
            val resolver = CapabilityResolver(mapOf(
                "workspace.cat" to { params: List<String> -> CapabilityResolver.Resolution.Comando(listOf("cat") + params) }
            ))
            val (agentSession, workspace) = session(root, authorization = authorization(capability = "workspace.cat"), capabilityResolver = resolver)
            agentSession.escreverArquivo("entrada.txt", "conteudo-x")

            val outcome = agentSession.rodarCapacidade(listOf("entrada.txt"))
            assertTrue(outcome is AgentSandboxSession.CommandOutcome.Completed)
            val log = (outcome as AgentSandboxSession.CommandOutcome.Completed).log
            assertEquals(0, log.exitCode)
            assertEquals("conteudo-x", log.stdout.trim())
            assertTrue(File(workspace, "entrada.txt").exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun `caminho que escapa do workspace e recusado`() {
        val root = Files.createTempDirectory("agent-session-").toFile()
        try {
            val (agentSession, _) = session(root)
            val result = agentSession.lerArquivo("../../fora.txt")
            assertTrue(result is AgentSandboxSession.FileOutcome.Refused)
        } finally { root.deleteRecursively() }
    }

    @Test fun `autorizacao expirada recusa novas chamadas`() {
        val root = Files.createTempDirectory("agent-session-").toFile()
        try {
            // A autorização precisa nascer válida — fromDecision recusa criar
            // a partir de uma decisão já expirada (é assim que a Policy
            // impede "terreno parcial"). Simulamos a expiração *durante* a
            // sessão via um clock fake, em vez de pré-expirar a decisão.
            val validAuthorization = authorization()
            val logDir = File(root, "logs")
            val runtime = ManagedSandboxRuntime(TestLauncher(root), FileExecutionLogRepository(logDir), sessionId = "session-1")
            val hostDir = File(root, "home/sandbox/workspace/${validAuthorization.runId}")
            val futureClock = { Instant.now().plus(1, ChronoUnit.HOURS) }
            val agentSession = AgentSandboxSession(
                authorization = validAuthorization,
                runtime = runtime,
                workspaceHostDir = hostDir,
                workspaceGuestPath = "/home/sandbox/workspace/${validAuthorization.runId}",
                clock = futureClock
            )

            val result = agentSession.escreverArquivo("x.txt", "y")
            assertTrue(result is AgentSandboxSession.FileOutcome.Refused)
            assertEquals(AgentSandboxSession.Status.EXPIRED, agentSession.sessionStatus)
        } finally { root.deleteRecursively() }
    }

    @Test fun `orcamento de saida esgotado bloqueia chamadas seguintes`() {
        val root = Files.createTempDirectory("agent-session-").toFile()
        try {
            val resolver = CapabilityResolver(mapOf(
                "workspace.print" to { params: List<String> -> CapabilityResolver.Resolution.Comando(listOf("printf") + params) }
            ))
            val authorized = authorization(capability = "workspace.print", budget = mapOf("output_bytes" to 5L))
            val (agentSession, _) = session(root, authorization = authorized, capabilityResolver = resolver)

            val first = agentSession.rodarCapacidade(listOf("0123456789"))
            assertTrue(first is AgentSandboxSession.CommandOutcome.Completed)
            assertEquals(AgentSandboxSession.Status.BUDGET_EXCEEDED, agentSession.sessionStatus)

            val second = agentSession.rodarCapacidade(listOf("mais"))
            assertTrue(second is AgentSandboxSession.CommandOutcome.Refused)
        } finally { root.deleteRecursively() }
    }

    @Test fun `sessao fechada recusa qualquer chamada seguinte`() {
        val root = Files.createTempDirectory("agent-session-").toFile()
        try {
            val (agentSession, _) = session(root)
            agentSession.close()
            val result = agentSession.listarArquivos(".")
            assertTrue(result is AgentSandboxSession.FileOutcome.Refused)
        } finally { root.deleteRecursively() }
    }

    @Test fun `rodarCapacidade usa a capacidade fixada na autorizacao, nunca uma escolhida pelo agente`() {
        val root = Files.createTempDirectory("agent-session-").toFile()
        try {
            val fakeCatalog = CapabilityResolver(mapOf(
                "sandbox.hello" to { params: List<String> -> CapabilityResolver.Resolution.Comando(listOf("echo", "capacidade-ok") + params) }
            ))
            val auth = authorization(capability = "sandbox.hello")
            val (agentSession, _) = session(root, authorization = auth, capabilityResolver = fakeCatalog)

            val outcome = agentSession.rodarCapacidade()
            assertTrue(outcome is AgentSandboxSession.CommandOutcome.Completed)
            val log = (outcome as AgentSandboxSession.CommandOutcome.Completed).log
            assertEquals(0, log.exitCode)
            assertEquals("capacidade-ok", log.stdout.trim())
        } finally { root.deleteRecursively() }
    }

    @Test fun `rodarCapacidade recusa quando a capacidade autorizada nao esta no catalogo do resolver`() {
        val root = Files.createTempDirectory("agent-session-").toFile()
        try {
            val vazio = CapabilityResolver(emptyMap())
            val auth = authorization(capability = "sandbox.nao_catalogado")
            val (agentSession, _) = session(root, authorization = auth, capabilityResolver = vazio)

            val outcome = agentSession.rodarCapacidade()
            assertTrue(outcome is AgentSandboxSession.CommandOutcome.Refused)
            // sessão continua OPEN — recusa de capacidade não é o mesmo que budget/expiração estourados.
            assertEquals(AgentSandboxSession.Status.OPEN, agentSession.sessionStatus)
        } finally { root.deleteRecursively() }
    }

    /** Roda comandos de verdade no host (não em proot) — mesma técnica do restante de :android-module. */
    private class TestLauncher(private val rootDir: File) : SandboxProcessLauncher {
        override fun launch(command: List<String>, workingDir: String): Process {
            val hostDir = File(rootDir, workingDir.removePrefix("/")).apply { mkdirs() }
            return ProcessBuilder(command).directory(hostDir).start()
        }
    }
}
