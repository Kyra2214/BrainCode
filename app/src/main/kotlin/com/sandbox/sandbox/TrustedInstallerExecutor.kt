package com.sandbox.sandbox

import com.sandbox.runtime.ExecutionLog
import java.security.MessageDigest

/**
 * Única fonte dos scripts `bash -c` de instalação/remoção via apt-get.
 *
 * Antes desta classe, o mesmo texto de script era montado de forma independente em
 * PluginModels.kt (instalação e remoção genéricas por pacote) e em ToolchainModels.kt
 * (ToolchainDetector.planInstall). Isso significa duas chances de divergência textual
 * e, mais importante para a Fase 1, duas fontes que o [TrustedInstallerCatalog] teria
 * que replicar para calcular os hashes confiáveis. Centralizando aqui, o catálogo de
 * hashes nunca pode ficar dessincronizado do script que de fato é executado.
 */
internal object AptScripts {
    fun install(packages: List<String>): String =
        "set -o pipefail; " +
            "export DEBIAN_FRONTEND=noninteractive; " +
            "if ! find /var/lib/apt/lists -type f -print -quit 2>/dev/null | grep -q .; then " +
            "apt-get update -qq || exit \$?; " +
            "fi; " +
            "apt-get -o Dpkg::Use-Pty=0 install -y --no-install-recommends --fix-missing " +
            packages.joinToString(" ") + " 2>&1 | tail -n 120"

    fun remove(packages: List<String>): String =
        "export DEBIAN_FRONTEND=noninteractive; " +
            "apt-get -o Dpkg::Use-Pty=0 remove -y ${packages.joinToString(" ")} 2>&1 | tail -n 120"
}

/** Uma checagem de ferramenta de linha de comando usada pelo "Teste geral" (self-check). */
data class CliToolCheck(val label: String, val script: String)

/**
 * Scripts fixos do self-check ("Teste geral"). Vivem aqui — não em SandboxViewModel —
 * para que o mesmo texto usado na UI seja o mesmo hasheado pelo [TrustedInstallerCatalog]
 * e executado pelo caminho de produção real, em vez de rodar direto no runtime bruto.
 */
object SelfCheckCliTools {
    val checks: List<CliToolCheck> = listOf(
        CliToolCheck("git", "git --version"),
        CliToolCheck("curl", "curl --version | head -n 1"),
        CliToolCheck("sqlite3", "sqlite3 --version"),
        CliToolCheck("make", "make --version | head -n 1"),
        CliToolCheck("zip/unzip", "zip -v | head -n 1 && unzip -v | head -n 1"),
        CliToolCheck("pip (python3 -m pip)", "python3 -m pip --version"),
        CliToolCheck("npm", "npm -v")
    )
}

/**
 * Todos os scripts `bash -c` que o [TrustedInstallerExecutor] aceita, derivados
 * diretamente do catálogo real (BuiltInCatalog, BuiltInToolchains, SelfCheckCliTools) —
 * nunca uma lista mantida à mão em paralelo, que poderia divergir do catálogo.
 */
object TrustedInstallerCatalog {
    fun allTrustedScripts(): Set<String> {
        val fromToolchains = BuiltInToolchains.all.map { profile -> AptScripts.install(profile.packages) }
        val fromSelfCheck = SelfCheckCliTools.checks.map { it.script }
        return scriptsFor(BuiltInCatalog.all) + fromToolchains + fromSelfCheck
    }

    /**
     * Scripts confiáveis derivados de uma lista arbitrária de componentes — usada tanto
     * para o catálogo embutido (BuiltInCatalog) quanto para plugins remotos já aceitos
     * por [RemotePluginCatalog]. Um plugin remoto só chega aqui depois de passar pelo
     * portão de confiança do import (fonte na allowlist + hash SHA-256 do artefato
     * verificado); a partir daí seus scripts recebem o mesmo tratamento dos embutidos.
     */
    fun scriptsFor(components: List<SandboxComponent>): Set<String> = components.flatMap { component ->
        listOfNotNull(
            bashScriptOf(component.installCommand),
            bashScriptOf(component.removeCommand),
            bashScriptOf(component.validationCommand)
        ) + listOfNotNull(
            component.packages.takeIf { it.isNotEmpty() }?.let(AptScripts::install),
            component.packages.takeIf { it.isNotEmpty() }?.let(AptScripts::remove)
        )
    }.toSet()

    private fun bashScriptOf(command: List<String>?): String? =
        command?.takeIf { it.size == 3 && it[0] == "bash" && it[1] == "-c" }?.get(2)
}

/**
 * Executor dedicado à instalação de plugins/toolchains do catálogo interno e ao self-check.
 *
 * O [SecureCommandExecutor] bloqueia qualquer `bash`/`sh -c` como "shell livre" —
 * corretamente, pois comandos originados de chat, terminal ou agente nunca devem poder
 * rodar shell arbitrário. O problema é que o catálogo interno (BuiltInCatalog,
 * BuiltInToolchains) só consegue instalar coisas como Trivy, SOPS, Ollama, o Android NDK
 * ou pacotes apt justamente *através* de `bash -c` (pipes, `&&`, variáveis de ambiente) —
 * e caía na mesma regra, sempre falhando com "shell livre não é uma capacidade autorizada".
 *
 * Este executor resolve isso sem abrir mão do shell livre: ele aceita `bash -c <script>`
 * somente quando o hash SHA-256 do script bate com um dos hashes pré-computados a partir
 * do catálogo real ([TrustedInstallerCatalog]) — nunca aceita um script vindo de fora
 * desse conjunto fixo, então continua sem existir "capacidade de shell genérica".
 * Qualquer outro comando (não `bash -c`, ou `bash -c` com script fora do catálogo) é
 * delegado ao [SecureCommandExecutor] padrão, com a mesma política genérica de sempre.
 */
class TrustedInstallerExecutor(
    private val delegate: SandboxCommandExecutor,
    private val policy: SandboxSecurityPolicy = SandboxSecurityPolicy(),
    /**
     * Fonte dos scripts confiáveis, recalculada a cada chamada `bash -c` — não um Set
     * fixo capturado na construção. Isso é o que permite plugins remotos aceitos pelo
     * RemotePluginCatalog *depois* que este executor já existe (ex.: via
     * SandboxPlatform.importRemotePluginSnapshot) ficarem confiáveis sem precisar
     * recriar o executor. Ver "Opção 2" registrada em LEGADO_E_DECISOES.md.
     */
    private val trustedScriptsProvider: () -> Set<String> = TrustedInstallerCatalog::allTrustedScripts
) : SandboxCommandExecutor {

    private val fallback = SecureCommandExecutor(delegate, policy)

    override fun execute(command: List<String>, timeoutSeconds: Long, workingDir: String): ExecutionLog {
        require(command.isNotEmpty()) { "Comando vazio" }
        val isShellInvocation = command.size == 3 && command[0] in SHELL_INTERPRETERS && command[1] == "-c"
        if (!isShellInvocation) return fallback.execute(command, timeoutSeconds, workingDir)

        val script = command[2]
        val trustedHashes = trustedScriptsProvider().map(::sha256).toSet()
        require(sha256(script) in trustedHashes) {
            "script recusado: não corresponde a nenhum instalador do catálogo confiável (hash não reconhecido)"
        }
        // Mesmos limites genéricos de timeout/diretório do SecureCommandExecutor — a única
        // regra que este script já provou não precisar é o bloqueio de shell em si, porque
        // foi validado por hash contra o catálogo fixo, e não veio do chamador.
        require(timeoutSeconds in 1..policy.limits.maxTimeoutSeconds) { "Timeout excede o limite do Sandbox" }
        require(policy.allowedWorkingRoots.any { workingDir == it || workingDir.startsWith("$it/") }) { "Diretório de trabalho não permitido" }
        return delegate.execute(command, timeoutSeconds, workingDir)
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private companion object {
        val SHELL_INTERPRETERS = setOf("bash", "sh")
    }
}
